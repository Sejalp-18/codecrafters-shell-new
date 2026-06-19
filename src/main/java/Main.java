import java.util.Scanner;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String s = sc.nextLine();

            if (s.trim().isEmpty()) {
                continue;
            }

            List<String> parsedArgs = parseInput(s);
            if (parsedArgs.isEmpty()) {
                continue;
            }

            String command = parsedArgs.get(0);

            if (command.equals("exit")) {
                if (parsedArgs.size() == 1 || (parsedArgs.size() == 2 && parsedArgs.get(1).equals("0"))) {
                    return;
                }
            } else if (command.equals("echo")) {
                System.out.println(String.join(" ", parsedArgs.subList(1, parsedArgs.size())));
            } else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else if (command.equals("cd")) {
                if (parsedArgs.size() > 1) {
                    String targetDir = parsedArgs.get(1);
                    String expandedDir = targetDir;

                    if (targetDir.startsWith("~")) {
                        String homeEnv = System.getenv("HOME");
                        if (homeEnv != null) {
                            expandedDir = targetDir.replaceFirst("^~", homeEnv);
                        }
                    }

                    Path currentDir = Path.of(System.getProperty("user.dir"));
                    Path newPath = currentDir.resolve(expandedDir).normalize();

                    if (Files.isDirectory(newPath)) {
                        System.setProperty("user.dir", newPath.toString());
                    } else {
                        System.out.println("cd: " + targetDir + ": No such file or directory");
                    }
                }
            } else if (command.equals("type")) {
                if (parsedArgs.size() > 1) {
                    String cmd = parsedArgs.get(1);

                    if (cmd.equals("exit") || cmd.equals("echo") || cmd.equals("type") || cmd.equals("pwd")
                            || cmd.equals("cd")) {
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
                }
            } else {
                String pathEnv = System.getenv("PATH");
                boolean found = false;

                if (pathEnv != null) {
                    String[] directories = pathEnv.split(File.pathSeparator);

                    for (String dir : directories) {
                        Path executablePath = Path.of(dir, command);

                        if (Files.isRegularFile(executablePath) && Files.isExecutable(executablePath)) {
                            found = true;
                            try {
                                ProcessBuilder pb = new ProcessBuilder(parsedArgs);

                                pb.directory(new File(System.getProperty("user.dir")));
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
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static List<String> parseInput(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inArg = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                // Toggle single quote ONLY if we are not inside double quotes
                inSingleQuote = !inSingleQuote;
                inArg = true;
            } else if (c == '"' && !inSingleQuote) {
                // Toggle double quote ONLY if we are not inside single quotes
                inDoubleQuote = !inDoubleQuote;
                inArg = true;
            } else if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
                // Space acts as a delimiter ONLY if we are completely unquoted
                if (inArg) {
                    args.add(currentArg.toString());
                    currentArg.setLength(0);
                    inArg = false;
                }
            } else {
                // Append the character (this inherently handles concatenation)
                currentArg.append(c);
                inArg = true;
            }
        }

        if (inArg) {
            args.add(currentArg.toString());
        }

        return args;
    }
}