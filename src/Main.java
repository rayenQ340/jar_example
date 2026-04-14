
import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Python Output");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(500, 300);
            frame.setLocationRelativeTo(null);

            JTextArea outputArea = new JTextArea();
            outputArea.setEditable(false);
            outputArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
            frame.add(new JScrollPane(outputArea));

            frame.setVisible(true);

            new Thread(() -> {
                try {
                    String base = new java.io.File(
                            Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()
                    ).getParentFile().getPath();

                    // If running inside IntelliJ, go up to project root
                    if (base.contains("out")) {
                        base = new java.io.File(base).getParentFile().getParentFile().getPath();
                    }

                    String pythonPath = base + "\\out\\artifacts\\untitled1_jar\\scripts\\.venv\\Scripts\\python.exe";
                    String scriptPath = base + "\\out\\artifacts\\untitled1_jar\\scripts\\test.py";

                    ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream())
                    );

                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String l = line;
                        SwingUtilities.invokeLater(() -> outputArea.append(l + "\n"));
                    }

                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> outputArea.append("Error: " + e.getMessage()));
                }
            }).start();
        });
    }
}