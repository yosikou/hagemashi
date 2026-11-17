import java.util.jar.*;
import java.security.*;
import java.util.*;
import java.io.*;

// Verify every payload entry and the exact upload certificate, including modified/unsigned entries.
class VerifyAabSignature {
    public static void main(String[] args) {
        try {
            if(args.length != 2 || !args[1].matches("[a-f0-9]{64}"))throw new Exception();
            int signed = 0;
            try(JarFile jar = new JarFile(args[0], true)) {
                if(jar.getManifest() == null)throw new Exception();
                var entries = jar.entries();
                while(entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if(entry.isDirectory())continue;
                    try(var input = jar.getInputStream(entry)) { input.transferTo(OutputStream.nullOutputStream()); }
                    String name = entry.getName().toUpperCase(Locale.ROOT);
                    if(name.equals("META-INF/MANIFEST.MF") || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)"))continue;
                    var signers = entry.getCodeSigners();
                    if(signers == null || signers.length != 1)throw new Exception();
                    byte[] certificate = signers[0].getSignerCertPath().getCertificates().get(0).getEncoded();
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate));
                    if(!digest.equals(args[1]))throw new Exception();
                    signed++;
                }
            }
            if(signed == 0)throw new Exception();
            System.out.println("Verified signed AAB payload entries: " + signed);
            System.out.println("Upload certificate SHA-256: " + args[1]);
        } catch(Exception e) {
            System.err.println("AAB signature verification failed.");
            System.exit(1);
        }
    }
}
