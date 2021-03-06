package plugin;

import com.gigaspaces.start.SystemLocations;
import org.apache.commons.io.FileUtils;
import org.openspaces.admin.Admin;
import org.openspaces.admin.rest.CustomManagerResource;
import org.openspaces.admin.rest.Response;

import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import java.io.*;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


@CustomManagerResource
@Path("/update")
public class UpgradePuRestPlugin {
    @Context
    Admin admin;

    private static String getDirName(String archive) {
        int lastDot = archive.lastIndexOf(".");
        return archive.substring(0, lastDot);
    }

    @PUT
    @Path("/updatePu")
    public Response report(@QueryParam("oldResource") String oldResource, @QueryParam("newResource") String newResource) {
        SystemLocations systemLocations = SystemLocations.singleton();

        java.nio.file.Path oldVersionPathResource = systemLocations.work("RESTresources" + File.separator + oldResource);
        java.nio.file.Path newVersionPathResource = systemLocations.work("RESTresources" + File.separator + newResource);

        File oldPu = new File(oldVersionPathResource.toUri());
        File newPu = new File(newVersionPathResource.toUri());

        if (oldPu.exists()) {
            try {
                if (!oldPu.delete()) {
                    throw new RuntimeException("failed to delete old pu " + oldResource);
                }
                if (!newPu.renameTo(new File(oldVersionPathResource.toUri()))) {
                    return Response.status(500).entity("Failed to rename resource " + newResource).build();
                }

            } catch (Throwable e) {
                return Response.status(500).entity("Failed to replace resource " + oldResource + " with " + newResource + " due to: " + e.getMessage()).build();
            }
        } else {
            return Response.status(500).entity("could not find resource: " + oldResource).build();
        }


        String dirName = getDirName(oldResource);
        File oldPuDeployDir = new File(systemLocations.deploy(dirName).toUri());

        if (oldPuDeployDir.exists()) {
            try {
                FileUtils.deleteDirectory(oldPuDeployDir);
            } catch (IOException e) {
                return Response.status(500).entity("failed to delete old pu dir " + oldPuDeployDir + " due to: " + e.getMessage()).build();
            }

            try {
                unzip(oldPu, new File(systemLocations.deploy(dirName).toUri()));
                return Response.ok().entity("successfully replaced pu " + oldResource + " with " + newResource).build();
            } catch (Exception e) {
                return Response.status(500).entity("failed to unzip new pu jar " + newVersionPathResource + " due to: " + e.getMessage()).build();
            }

        } else {
            return Response.status(500).entity("could not find old pu deploy dir " + oldPuDeployDir).build();
        }
    }

    private void unzip(File targetZip, File dirToExtract) throws Exception {
        Logger logger = Logger.getLogger("MyLogger");
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Unzipping file [" + targetZip.getAbsolutePath() + "] with size [" + targetZip.length() + "] to [" + dirToExtract.getAbsolutePath() + "]");
        }

        final int bufferSize = 4098;
        byte[] data = new byte[bufferSize];
        try (ZipFile zipFile = new ZipFile(targetZip)) {
            Enumeration<? extends ZipEntry> e = zipFile.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) {
                    File dir = new File(dirToExtract.getAbsolutePath() + "/" + entry.getName().replace('\\', '/'));
                    for (int i = 0; i < 5; i++) {
                        dir.mkdirs();
                    }
                } else {
                    File file = new File(dirToExtract.getAbsolutePath() + "/" + entry.getName().replace('\\', '/'));
                    try (BufferedInputStream is = new BufferedInputStream(zipFile.getInputStream(entry))) {
                        if (file.getParentFile() != null) {
                            file.getParentFile().mkdirs();
                        }
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.finest("Extracting zip entry [" + entry.getName() + "] with size [" + entry.getSize() + "] to [" + file.getAbsolutePath() + "]");
                        }
                        FileOutputStream fos = new FileOutputStream(file);
                        int count;
                        try (BufferedOutputStream dest = new BufferedOutputStream(fos, bufferSize)) {
                            while ((count = is.read(data, 0, bufferSize)) != -1) {
                                dest.write(data, 0, count);
                            }
                        }
                    }

                    // sync the file to the file system
                    try (RandomAccessFile ras = new RandomAccessFile(file, "rw")) {
                        ras.getFD().sync();
                    }
                }
            }
        }
    }

}
