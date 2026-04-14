import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validator for Belgian fiscal form (fiche 281.10 / 281.20 style).
 * Covers mandatory fields, format rules, and conditional logic.
 */
public class FicheValidator {

    // -----------------------------------------------------------------------
    // Constants & Patterns
    // -----------------------------------------------------------------------

    /** Belgian BCE/KBO: 10 digits, typically formatted as 0XXX.XXX.XXX */
    private static final Pattern BCE_PATTERN =
            Pattern.compile("^(0|1)\\d{3}\\.?\\d{3}\\.?\\d{3}$");

    /** Belgian national number: YY.MM.DD-XXX.CC (11 raw digits accepted too) */
    private static final Pattern NRNN_PATTERN =
            Pattern.compile("^\\d{2}\\.?\\d{2}\\.?\\d{2}[-.]?\\d{3}[.-]?\\d{2}$");

    /** Belgian postal code: 4 digits, 1000–9999 */
    private static final Pattern POSTAL_BE_PATTERN =
            Pattern.compile("^[1-9]\\d{3}$");

    /** A name: letters (including accented), hyphens, apostrophes, spaces; NO digits/symbols */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[\\p{L}\\s'\\-]{1,100}$");

    /** NIF / foreign tax ID: alphanumeric, 5–20 chars */
    private static final Pattern NIF_PATTERN =
            Pattern.compile("^[A-Za-z0-9\\-]{5,20}$");

    /** Numéro de fiche: numeric string (e.g. zone 2.009) */
    private static final Pattern FICHE_NUM_PATTERN =
            Pattern.compile("^\\d{1,15}$");

    /** Advantage codes (zone 2.x): A–Z single uppercase letter */
    private static final Pattern ADVANTAGE_CODE_PATTERN =
            Pattern.compile("^[A-Z]$");

    /** Hours in centièmes: positive number with at most 2 decimal places */
    private static final Pattern HOURS_PATTERN =
            Pattern.compile("^\\d{1,5}(\\.\\d{1,2})?$");

    private static final List<DateTimeFormatter> ACCEPTED_DATE_FORMATS = Arrays.asList(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("ddMMyyyy")
    );

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /**
     * Validate a full fiche represented as a flat map of zone → value.
     *
     * @param zones key = zone code (e.g. "2.009"), value = raw string value
     * @return list of {@link ValidationError}; empty list means the fiche is valid
     */
    public static List<ValidationError> validate(Map<String, String> zones) {
        List<ValidationError> errors = new ArrayList<>();

        // --- Mandatory: Numéro de fiche (2.009) --------------------------------
        validateFicheNumber(zones, errors);

        // --- Mandatory: Dates d'entrée/sortie (2.055, 2.056) ------------------
        validateEntryExitDates(zones, errors);

        // --- Débiteur des revenus (1.011, 1.012, 1.014, 1.015) ---------------
        validateDebiteur(zones, errors);

        // --- BCE / Numéro d'entreprise (1.005) --------------------------------
        validateBCE(zones, errors);

        // --- Numéro national (1.037) ------------------------------------------
        validateNationalNumber(zones, errors);

        // --- Identification bénéficiaire (2.013–2.018) ------------------------
        validateBeneficiaire(zones, errors);

        // --- Identifiant bénéficiaire, zone 5 (2.011 / 2.012+2.105 / 2.109) --
        validateIdentifiantBeneficiaire(zones, errors);

        // --- Rémunérations section 6a (2.060) ---------------------------------
        validateRemuneration(zones, errors);

        // --- Avantages en nature (code + montant couple) ----------------------
        validateAvantages(zones, errors);

        // --- Heures (centièmes, not minutes) ----------------------------------
        validateHeures(zones, errors);

        // --- HORECA sections 17 / 18 mutual exclusion -------------------------
        validateHoreca(zones, errors);

        return Collections.unmodifiableList(errors);
    }

    // -----------------------------------------------------------------------
    // Individual validators
    // -----------------------------------------------------------------------

    /** Zone 2.009 – numéro de fiche, obligatoire, numeric */
    private static void validateFicheNumber(Map<String, String> zones,
                                            List<ValidationError> errors) {
        String val = get(zones, "2.009");
        if (isEmpty(val)) {
            errors.add(ValidationError.mandatory("2.009", "Numéro de fiche"));
        } else if (!FICHE_NUM_PATTERN.matcher(val.trim()).matches()) {
            errors.add(ValidationError.format("2.009",
                    "Numéro de fiche invalide (chiffres uniquement) : " + val));
        }
    }

    /** Zones 2.055 & 2.056 – dates d'entrée et de sortie */
    private static void validateEntryExitDates(Map<String, String> zones,
                                               List<ValidationError> errors) {
        validateDate(zones, "2.055", "Date d'entrée", true, errors);
        validateDate(zones, "2.056", "Date de sortie", true, errors);

        // If both present, entry must be <= exit
        LocalDate entry = parseDate(get(zones, "2.055"));
        LocalDate exit  = parseDate(get(zones, "2.056"));
        if (entry != null && exit != null && entry.isAfter(exit)) {
            errors.add(new ValidationError("2.055/2.056", ErrorType.LOGIC,
                    "La date d'entrée (2.055) est postérieure à la date de sortie (2.056)."));
        }
    }

    /** Zones 1.011 / 1.012 – nom débiteur; 1.014 – code postal; 1.015 – commune */
    private static void validateDebiteur(Map<String, String> zones,
                                         List<ValidationError> errors) {
        validateName(zones, "1.011", "Nom débiteur (ligne 1)", false, errors);
        validateName(zones, "1.012", "Nom débiteur (ligne 2)", false, errors);

        // At least one of 1.011 / 1.012 must be filled
        if (isEmpty(get(zones, "1.011")) && isEmpty(get(zones, "1.012"))) {
            errors.add(ValidationError.mandatory("1.011/1.012",
                    "Au moins une des zones nom débiteur doit être renseignée"));
        }

        validatePostalCode(zones, "1.014", "Code postal débiteur", false, errors);
        validateCommune(zones, "1.015", "Commune débiteur", false, errors);
    }

    /** Zone 1.005 – numéro BCE */
    private static void validateBCE(Map<String, String> zones,
                                    List<ValidationError> errors) {
        String val = get(zones, "1.005");
        if (isEmpty(val)) {
            errors.add(ValidationError.mandatory("1.005", "Numéro d'entreprise (BCE)"));
        } else {
            String normalized = val.trim().replaceAll("\\s", "");
            if (!BCE_PATTERN.matcher(normalized).matches()) {
                errors.add(ValidationError.format("1.005",
                        "Numéro BCE invalide (format attendu: 0XXX.XXX.XXX) : " + val));
            } else if (!isBCEChecksumValid(normalized)) {
                errors.add(ValidationError.format("1.005",
                        "Numéro BCE invalide (chiffre de contrôle incorrect) : " + val));
            }
        }
    }

    /** Zone 1.037 – numéro national du débiteur */
    private static void validateNationalNumber(Map<String, String> zones,
                                               List<ValidationError> errors) {
        String val = get(zones, "1.037");
        if (isEmpty(val)) {
            errors.add(ValidationError.mandatory("1.037", "Numéro national débiteur"));
        } else {
            validateNRNN(val, "1.037", "Numéro national débiteur", errors);
        }
    }

    /**
     * Zones 2.013 (nom), 2.114 (prénom), 2.015 (rue), 2.016 (code postal),
     * 2.017 (commune), 2.018 (pays) – tous obligatoires
     */
    private static void validateBeneficiaire(Map<String, String> zones,
                                             List<ValidationError> errors) {
        requireField(zones, "2.013", "Nom bénéficiaire", errors);
        requireField(zones, "2.114", "Prénom bénéficiaire", errors);
        requireField(zones, "2.015", "Rue bénéficiaire", errors);
        validatePostalCode(zones, "2.016", "Code postal bénéficiaire", true, errors);
        validateCommune(zones, "2.017", "Commune bénéficiaire", true, errors);
        requireField(zones, "2.018", "Pays bénéficiaire", errors);

        // Name must not contain digits or symbols
        validateName(zones, "2.013", "Nom bénéficiaire", true, errors);
    }

    /**
     * Zone 5 – au moins un identifiant parmi:
     *   2.011 (N° national / Bis)
     *   2.012 (date naissance) + 2.105 (lieu naissance)
     *   2.109 (NIF)
     */
    private static void validateIdentifiantBeneficiaire(Map<String, String> zones,
                                                        List<ValidationError> errors) {
        String nrnn    = get(zones, "2.011");
        String dob     = get(zones, "2.012");
        String lieu    = get(zones, "2.105");
        String nif     = get(zones, "2.109");

        boolean hasNRNN = !isEmpty(nrnn) && isValidNRNN(nrnn);
        boolean hasDOBAndLieu = !isEmpty(dob) && !isEmpty(lieu) && parseDate(dob) != null;
        boolean hasNIF  = !isEmpty(nif) && NIF_PATTERN.matcher(nif.trim()).matches();

        if (!hasNRNN && !hasDOBAndLieu && !hasNIF) {
            errors.add(new ValidationError("2.011/2.012/2.105/2.109", ErrorType.MANDATORY,
                    "Zone 5 : au moins un identifiant obligatoire — " +
                            "N° national (2.011), ou date+lieu naissance (2.012+2.105), ou NIF (2.109)."));
        }

        // Validate individual fields if present
        if (!isEmpty(nrnn)) {
            validateNRNN(nrnn, "2.011", "N° national bénéficiaire", errors);
        }
        if (!isEmpty(dob)) {
            validateDate(zones, "2.012", "Date de naissance bénéficiaire", false, errors);
        }
        if (!isEmpty(nif) && !NIF_PATTERN.matcher(nif.trim()).matches()) {
            errors.add(ValidationError.format("2.109",
                    "NIF invalide (alphanumérique, 5–20 caractères) : " + nif));
        }
    }

    /** Zone 2.060 – montant rémunération, obligatoire (0 est accepté) */
    private static void validateRemuneration(Map<String, String> zones,
                                             List<ValidationError> errors) {
        String val = get(zones, "2.060");
        if (val == null) {                       // null = not provided at all
            errors.add(ValidationError.mandatory("2.060",
                    "Montant des rémunérations (0 doit être déclaré explicitement)"));
        } else {
            try {
                double amount = Double.parseDouble(val.trim().replace(",", "."));
                if (amount < 0) {
                    errors.add(ValidationError.format("2.060",
                            "Le montant des rémunérations ne peut pas être négatif : " + val));
                }
            } catch (NumberFormatException e) {
                errors.add(ValidationError.format("2.060",
                        "Montant invalide (numérique attendu) : " + val));
            }
        }
    }

    /**
     * Avantages en nature: the code (zone 2.062 or similar) and the amount (2.063)
     * form an obligatory pair — one without the other is rejected.
     * Codes must be A–Z (single uppercase letter).
     */
    private static void validateAvantages(Map<String, String> zones,
                                          List<ValidationError> errors) {
        String code   = get(zones, "2.062");
        String amount = get(zones, "2.063");

        boolean hasCode   = !isEmpty(code);
        boolean hasAmount = !isEmpty(amount);

        if (hasCode != hasAmount) {
            errors.add(new ValidationError("2.062/2.063", ErrorType.CONDITIONAL,
                    "Le code avantage en nature (2.062) et son montant (2.063) " +
                            "sont un couple obligatoire — les deux doivent être renseignés ou absents."));
        }

        if (hasCode && !ADVANTAGE_CODE_PATTERN.matcher(code.trim()).matches()) {
            errors.add(ValidationError.format("2.062",
                    "Code avantage invalide (A–Z attendu, aligné à gauche) : " + code));
        }

        if (hasAmount) {
            try {
                double amt = Double.parseDouble(amount.trim().replace(",", "."));
                if (amt < 0) {
                    errors.add(ValidationError.format("2.063",
                            "Montant avantage négatif non autorisé : " + amount));
                }
            } catch (NumberFormatException e) {
                errors.add(ValidationError.format("2.063",
                        "Montant avantage invalide (numérique attendu) : " + amount));
            }
        }
    }

    /**
     * Hours (e.g. zone 2.070) must be expressed in centièmes (hundredths),
     * NOT in minutes. Common mistake: 90 minutes → should be 1.50, not 90.
     * Validation: must be a positive decimal with at most 2 decimal places.
     */
    private static void validateHeures(Map<String, String> zones,
                                       List<ValidationError> errors) {
        String val = get(zones, "2.070");
        if (!isEmpty(val)) {
            String normalized = val.trim().replace(",", ".");
            if (!HOURS_PATTERN.matcher(normalized).matches()) {
                errors.add(ValidationError.format("2.070",
                        "Heures invalides — exprimer en centièmes (ex: 1.50 = 1h30) : " + val));
            } else {
                // Heuristic: if value > 5000 it's almost certainly entered in minutes
                try {
                    double h = Double.parseDouble(normalized);
                    if (h > 5000) {
                        errors.add(new ValidationError("2.070", ErrorType.FORMAT,
                                "Valeur d'heures suspecte (" + val + ") — " +
                                        "vérifiez que vous utilisez les centièmes, pas les minutes."));
                    }
                } catch (NumberFormatException ignored) { /* already caught above */ }
            }
        }
    }

    /**
     * HORECA sections 17 and 18 are mutually exclusive.
     * Here assumed as zones 2.080 and 2.081 (adjust zone codes as needed).
     */
    private static void validateHoreca(Map<String, String> zones,
                                       List<ValidationError> errors) {
        String sec17 = get(zones, "2.080");
        String sec18 = get(zones, "2.081");

        boolean has17 = !isEmpty(sec17);
        boolean has18 = !isEmpty(sec18);

        if (has17 && has18) {
            errors.add(new ValidationError("2.080/2.081", ErrorType.LOGIC,
                    "Les sections HORECA 17 (2.080) et 18 (2.081) sont mutuellement exclusives " +
                            "— une seule peut être renseignée."));
        }
    }

    // -----------------------------------------------------------------------
    // Helper validators
    // -----------------------------------------------------------------------

    private static void validateDate(Map<String, String> zones, String zone,
                                     String label, boolean mandatory,
                                     List<ValidationError> errors) {
        String val = get(zones, zone);
        if (isEmpty(val)) {
            if (mandatory) errors.add(ValidationError.mandatory(zone, label));
            return;
        }
        if (parseDate(val) == null) {
            errors.add(ValidationError.format(zone,
                    label + " — date invalide (formats acceptés: dd/MM/yyyy, yyyy-MM-dd) : " + val));
        }
    }

    private static void validateName(Map<String, String> zones, String zone,
                                     String label, boolean mandatory,
                                     List<ValidationError> errors) {
        String val = get(zones, zone);
        if (isEmpty(val)) {
            if (mandatory) errors.add(ValidationError.mandatory(zone, label));
            return;
        }
        if (!NAME_PATTERN.matcher(val.trim()).matches()) {
            errors.add(ValidationError.format(zone,
                    label + " invalide — les chiffres et symboles ne sont pas autorisés : " + val));
        }
    }

    private static void validatePostalCode(Map<String, String> zones, String zone,
                                           String label, boolean mandatory,
                                           List<ValidationError> errors) {
        String val = get(zones, zone);
        if (isEmpty(val)) {
            if (mandatory) errors.add(ValidationError.mandatory(zone, label));
            return;
        }
        if (!POSTAL_BE_PATTERN.matcher(val.trim()).matches()) {
            errors.add(ValidationError.format(zone,
                    label + " invalide (code postal belge 4 chiffres, 1000–9999) : " + val));
        }
    }

    private static void validateCommune(Map<String, String> zones, String zone,
                                        String label, boolean mandatory,
                                        List<ValidationError> errors) {
        String val = get(zones, zone);
        if (isEmpty(val)) {
            if (mandatory) errors.add(ValidationError.mandatory(zone, label));
            return;
        }
        if (val.trim().length() < 2 || val.trim().length() > 60) {
            errors.add(ValidationError.format(zone,
                    label + " invalide (2–60 caractères attendus) : " + val));
        }
    }

    private static void validateNRNN(String val, String zone, String label,
                                     List<ValidationError> errors) {
        if (!isValidNRNN(val)) {
            errors.add(ValidationError.format(zone,
                    label + " invalide (format: YY.MM.DD-XXX.CC) : " + val));
        }
    }

    private static void requireField(Map<String, String> zones, String zone,
                                     String label, List<ValidationError> errors) {
        if (isEmpty(get(zones, zone))) {
            errors.add(ValidationError.mandatory(zone, label));
        }
    }

    // -----------------------------------------------------------------------
    // Pure utility
    // -----------------------------------------------------------------------

    private static String get(Map<String, String> zones, String zone) {
        return zones.getOrDefault(zone, null);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        for (DateTimeFormatter fmt : ACCEPTED_DATE_FORMATS) {
            try { return LocalDate.parse(s, fmt); } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private static boolean isValidNRNN(String raw) {
        if (raw == null) return false;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() != 11) return false;
        // Standard modulo-97 check
        long base = Long.parseLong(digits.substring(0, 9));
        int  check = Integer.parseInt(digits.substring(9, 11));
        // Born before 2000
        if ((97 - (int)(base % 97)) == check) return true;
        // Born from 2000 onward: prepend '2'
        long base2000 = Long.parseLong("2" + digits.substring(0, 9));
        return (97 - (int)(base2000 % 97)) == check;
    }

    /**
     * BCE checksum: last two digits = 97 − (first 8 digits mod 97).
     * The number is stored without the leading country prefix here.
     */
    private static boolean isBCEChecksumValid(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() != 10) return false;
        long base  = Long.parseLong(digits.substring(0, 8));
        int  check = Integer.parseInt(digits.substring(8, 10));
        return (97 - (int)(base % 97)) == check;
    }

    // -----------------------------------------------------------------------
    // Result model
    // -----------------------------------------------------------------------

    public enum ErrorType { MANDATORY, FORMAT, LOGIC, CONDITIONAL }

    public static class ValidationError {
        public final String   zone;
        public final ErrorType type;
        public final String   message;

        public ValidationError(String zone, ErrorType type, String message) {
            this.zone    = zone;
            this.type    = type;
            this.message = message;
        }

        static ValidationError mandatory(String zone, String label) {
            return new ValidationError(zone, ErrorType.MANDATORY,
                    "Zone " + zone + " obligatoire : " + label + " manquant(e).");
        }

        static ValidationError format(String zone, String detail) {
            return new ValidationError(zone, ErrorType.FORMAT, "Zone " + zone + " : " + detail);
        }

        @Override
        public String toString() {
            return "[" + type + "] Zone " + zone + " — " + message;
        }
    }

    // -----------------------------------------------------------------------
    // Demo main
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        Map<String, String> fiche = new LinkedHashMap<>();

        // ---- Fill with sample data ----
        fiche.put("2.009", "123456789");           // numéro de fiche ✔
        fiche.put("2.055", "01/01/2024");          // date entrée ✔
        fiche.put("2.056", "31/12/2024");          // date sortie ✔
        fiche.put("1.011", "Dupont");              // nom débiteur ✔
        fiche.put("1.012", "");
        fiche.put("1.014", "1000");                // code postal ✔
        fiche.put("1.015", "Bruxelles");           // commune ✔
        fiche.put("1.005", "0123.456.749");        // BCE (checksum may fail — demo)
        fiche.put("1.037", "85.07.30-154.23");     // numéro national ✔
        fiche.put("2.013", "Martin");              // nom bénéficiaire ✔
        fiche.put("2.114", "Sophie");              // prénom bénéficiaire ✔
        fiche.put("2.015", "Rue de la Loi 1");    // rue ✔
        fiche.put("2.016", "1040");               // code postal bénéficiaire ✔
        fiche.put("2.017", "Etterbeek");          // commune ✔
        fiche.put("2.018", "BE");                 // pays ✔
        fiche.put("2.011", "85.07.30-154.23");    // N° national bénéficiaire ✔
        fiche.put("2.060", "24500.00");           // rémunération ✔
        fiche.put("2.062", "A");                  // code avantage ✔
        fiche.put("2.063", "1200.00");            // montant avantage ✔
        fiche.put("2.070", "1760.50");            // heures en centièmes ✔
        // 2.080 / 2.081 not set → no HORECA conflict

        List<ValidationError> errors = validate(fiche);

        if (errors.isEmpty()) {
            System.out.println("✔ La fiche est valide.");
        } else {
            System.out.println("✖ " + errors.size() + " erreur(s) détectée(s) :");
            errors.forEach(e -> System.out.println("  " + e));
        }
    }
}