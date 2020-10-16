import java.io.*;
import java.net.*;
import java.util.Date;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * This program demonstrates a simple TCP/IP socket server.
 *
 * @author www.codejava.net
 */
public class TimeServer {


    public static void main(String[] args) {
        if (args.length < 1) return;


        Process p, p1;

        try {
            System.out.println("Running cmds...");

            //kill current supplicant and avoid its auto creation

            //move the service out of system-services to avoid auto-creation
            p = Runtime.getRuntime().exec("sudo mv /usr/share/dbus-1/system-services/fi.w1.wpa_supplicant1.service .");
            p.waitFor();

            System.out.println("Move done");

            //kill udhcpd server already running on pc
            p = Runtime.getRuntime().exec("sudo killall udhcpd");
            p.waitFor();

            System.out.println("Kill udhcpd done");

            //terminate wpa_supplicant in background
            p = Runtime.getRuntime().exec("sudo wpa_cli -i wlp3s0 terminate -B");
            p.waitFor();

            System.out.println("wpa_supplicant termination in background completed");

            //start new supplicant in the background
            Runtime.getRuntime().exec("sudo wpa_supplicant -i wlp3s0 -c /etc/wpa_supplicant.conf -B");
            //can't wait for wpa_supplicant to finish because it's ongoing

            System.out.println("Wpa_supplicant started, waiting...");

            //give wpa_supplicant time to initialize
            TimeUnit.SECONDS.sleep(8);

            p = Runtime.getRuntime().exec("sudo wpa_cli -i wlp3s0 p2p_find");
            p.waitFor();



            TimeUnit.SECONDS.sleep(3);

            p = Runtime.getRuntime().exec("sudo wpa_cli -i wlp3s0 p2p_peers");
            p.waitFor();



            TimeUnit.SECONDS.sleep(3);

            
            //become p2p group owner
            //p = Runtime.getRuntime().exec("sudo wpa_cli -i wlp3s0 p2p_group_add");
            //p.waitFor();

            System.out.println("p2p_group_add done");

            //assign ip address to wlp3s0-0
            //p = Runtime.getRuntime().exec("sudo ifconfig p2p-wlp3s0-0 10.1.10.133");
            //p.waitFor();

            System.out.println("ifconfig done");
        }
        catch (IOException e) {

            e.printStackTrace();
        } 
  
  
        catch (InterruptedException e) {

            e.printStackTrace();
        }
        

        //get the passed port number
        int port = Integer.parseInt(args[0]);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            
            System.out.println("Server is listening on port " + port);

            //listen for clients
            while (true) {

                try {
                    //re-enter wpa_cli using newly created p2p-wlp3s0-0 interface
                    //intitiate pushbutton connection event
                    p1 = Runtime.getRuntime().exec("sudo wpa_cli -i wlp3s0 p2p_connect 7a:4a:4b:cd:0c:62 pbc go_intent=15"); //for some reason reusing p was not working
                    p1.waitFor();

                    System.out.println("wps_pbc done");

                    p1 = Runtime.getRuntime().exec("sudo udhcpd /etc/udhcpd.conf");
                    p1.waitFor();

                    System.out.println("udhcpd started");
                }


                catch (IOException e) {

                    e.printStackTrace();
                } 

                
                catch (InterruptedException e) {

                    e.printStackTrace();
                }

                System.out.println("Waiting for client to join...");

                //this call blocks until client connects
                Socket client = serverSocket.accept();

                System.out.println("New client connected");

                //OutputStream output = socket.getOutputStream();

                byte[] in = new byte[10];

                //now a client has initialized and transferred/output data via stream
                //now we want to save the input stream from the client
                InputStream inputStream = client.getInputStream();

                int charsRead = inputStream.read(in);

                System.out.println("Got message from client: " + in[0]);

                serverSocket.close();

                //PrintWriter writer = new PrintWriter(output, true);

                //writer.println(new Date().toString());
            }

        } 
        
        
        catch (IOException ex) {
            System.out.println("Server exception: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}