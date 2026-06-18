import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String s = sc.nextLine();
            if (s.equals("exit"))
                return;
            if (s.startsWith("echo"))
                System.out.println(s.substring(5));

            else if (s.startsWith("type")) {

                if (s.endsWith("exit"))
                    System.out.println("exit is a shell builtin");
                else if (s.endsWith("echo"))
                    System.out.println("echo is a shell builtin");
                else if (s.endsWith("type"))
                    System.out.println("type is a shell builtin");
                else
                    System.out.println(s.substring(5) + ": not found");
            } else
                System.out.println(s + ": command not found");
        }

    }
}
