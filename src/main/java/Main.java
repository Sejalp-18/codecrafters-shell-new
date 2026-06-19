import java.util.Scanner;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
// You might need java.util.Arrays if you convert array to list, but ProcessBuilder takes varargs/arrays.

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String s = sc.nextLine();

            if (s.trim().isEmpty()) {
                continue;
            }

            if (s.equals("exit") || s.equals("exit 0")) {
                return;
            } else if (s.startsWith("echo ")) {
                System.out.println(s.substring(5));
            } else if (s.startsWith("type ")) {
                String cmd = s.substring(5);

                if (cmd.equals("exit") || cmd.equals("echo") || cmd.equals("type")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String pathEnv = System.getenv("PATH");
                    boolean found = false;

                    if (pathEnv != null) {
                        String[] directories = pathEnv.split(File.pathSeparator);

                        for (String dir : directories) {
                            Path executablePath = Path.of(dir, cmd);

                            if (Files.isRegularFile(executablePath) && Files.isExecutable(executablePath)) {
                                System.out.println(cmd + " is " + executablePath);
                                found = true;
                                break;
                            }
                        }
                    }

                    if (!found) {
                        System.out.println(cmd + ": not found");
                    }
                }
            } else {
                String[] parts = s.split(" ");
                String cmd = parts[0];

                String pathEnv = System.getenv("PATH");
                boolean found = false;

                if (pathEnv != null) {
                    String[] directories = pathEnv.split(File.pathSeparator);

                    for (String dir : directories) {
                        Path executablePath = Path.of(dir, cmd);

                        if (Files.isRegularFile(executablePath) && Files.isExecutable(executablePath)) {
                            found = true;
                            try {
                                ProcessBuilder pb = new ProcessBuilder(parts);
                                pb.inheritIO();

                                Process process = pb.start();
                                process.waitFor();
                            } catch (Exception e) {
                                System.out.println("Error executing program: " + e.getMessage());
                            }
                            break;
                        }
                    }
                }

                if (!found) {
                    System.out.println(s + ": command not found");
                }
            }
        }
    }
}