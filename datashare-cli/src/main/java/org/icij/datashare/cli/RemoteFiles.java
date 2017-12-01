package org.icij.datashare.cli;

import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class RemoteFiles {
    private final AmazonS3 s3Client;
    private final String bucket;

    public RemoteFiles(final AmazonS3 s3Client, final String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public void upload(final File localFile, final String remoteKey) throws InterruptedException, FileNotFoundException {
        if (localFile.isDirectory()) {
            TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(this.s3Client).build();
            final MultipleFileUpload uploads = transferManager.uploadDirectory(bucket, remoteKey, localFile, true);

            for (Upload upload : uploads.getSubTransfers()) {
                upload.waitForUploadResult();
            }
            transferManager.shutdownNow();
        } else {
            final ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(localFile.length());
            s3Client.putObject(new PutObjectRequest(bucket, remoteKey, new FileInputStream(localFile), metadata));
        }
    }

    public void download(final String remoteKey, final File localFile) throws InterruptedException, IOException {
        if (localFile.isDirectory()) {
            TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(this.s3Client).build();
            transferManager.downloadDirectory(bucket, remoteKey, localFile).waitForCompletion();
            transferManager.shutdownNow();
        } else {
            final S3Object s3Object = s3Client.getObject(this.bucket, remoteKey);
            Files.copy(s3Object.getObjectContent(), Paths.get(localFile.getPath()));
        }
    }

    public boolean objectExists(final String key) {
        return s3Client.doesObjectExist(this.bucket, key);
    }

    public static void main(String[] args) throws Exception {
        AmazonS3 amazonS3 = AmazonS3ClientBuilder.standard().withRegion("us-east-1")
                .withCredentials(new ClasspathPropertiesFileCredentialsProvider("s3.properties")).build();
        final RemoteFiles remoteFiles = new RemoteFiles(amazonS3, "s3.datashare.icij.org");

        OptionSet cmd = parseArgs(args);
        String remoteKey = cmd.has("D") ? (String)cmd.valueOf("D") : "/";
        if (cmd.has("u")) {
            for (Object arg : cmd.valuesOf("f")) {
                remoteFiles.upload(new File((String) arg), remoteKey);
            }
        } else if (cmd.has("d")) {
            for (Object arg : cmd.valuesOf("f")) {
                remoteFiles.download(remoteKey, new File((String) arg));
            }
        } else {
            usage();
        }
    }

    private static OptionSet parseArgs(final String[] args) {
        OptionParser parser = new OptionParser("udD:f:");
        OptionSet optionSet = null;
        try {
            optionSet = parser.parse(args);
        } catch (Exception e) {
            usage();
            System.exit(1);
        }
        return optionSet;
    }

    private static void usage() {
        System.out.println("usage : copy-remote [-u|-d] [-D remoteDirectory] -f file1 -f file2...");
        System.out.println("-u: to upload content to remote destination");
        System.out.println("-d: to download content from remote destination");
        System.out.println("-D: remote directory (default : /)");
        System.out.println("-f: directories or files. For directory, the content will be uploaded recursively");
        System.out.println("example : copy-remote -u -D foo -f bar.txt -f qux/ ");
    }
}

