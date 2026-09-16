package slanglsp;

import io.netty.util.AsciiString;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SlangVersion
{
    private Integer major = 0;
    private Integer minor = 0;
    private Integer patch = 0;

    public static void writeSlangVersionFile(File dst, Integer major_t, Integer minor_t, Integer patch_t) throws Exception
    {
        if(dst.exists())
            dst.delete();

        try
        {
            dst.createNewFile();

            BufferedWriter bw = new BufferedWriter(new FileWriter(dst));
            bw.write(major_t.toString());
            bw.write(".");
            bw.write(minor_t.toString());
            bw.write(".");
            bw.write(patch_t.toString());
            bw.close();
        }
        catch(Exception e)
        {
            throw e;
        }
    }

    SlangVersion(InputStream versionFileStream)
    {
        try
        {
            var bytes = versionFileStream.readAllBytes();
            SlangVersion cachedVersion = new SlangVersion(new String(bytes, StandardCharsets.UTF_8));
            this.major = cachedVersion.major;
            this.minor = cachedVersion.minor;
            this.patch = cachedVersion.patch;
        }
        catch(Exception e)
        {
            System.out.println("Cannot read versionFileStream: "+versionFileStream);
        }
    }

    SlangVersion(String versionString)
    {
        try
        {
            String[] versionInfoString = versionString.split("\\.");
            major = Integer.valueOf(versionInfoString[0]);
            minor = Integer.valueOf(versionInfoString[1]);
            patch = Integer.valueOf(versionInfoString[2]);
        }
        catch(Exception e)
        {
            System.out.print("Invalid SlangVersion string: " + versionString);
        }
    }

    public Integer getMajor()
    {
        return major;
    }

    public Integer getMinor()
    {
        return minor;
    }

    public Integer getPatch()
    {
        return patch;
    }

    public boolean equals(Object obj)
    {
        if(!(obj instanceof SlangVersion))
            return false;

        SlangVersion otherVersion = (SlangVersion)obj;
        return this.getMajor().equals(otherVersion.getMajor()) && this.getMinor().equals(otherVersion.getMinor()) && this.getPatch().equals(otherVersion.getPatch());
    }
}
