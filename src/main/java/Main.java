import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String s = sc.nextLine();
            if (s.equals("exit"))
                return;
            else if (s.startsWith("echo"))
                System.out.println(s.substring(5));
            else
                System.out.println(s + ": command not found");
        }

    }
}
