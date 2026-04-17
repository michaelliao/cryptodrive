package org.puppylab.cryptodrive.util;

import java.net.URI;

import org.puppylab.cryptodrive.core.VaultConfig;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

public class S3Utils {

    public static S3Client createS3Client(VaultConfig.S3Config s3config) {
        S3ClientBuilder scb = S3Client.builder().region(Region.of(s3config.region));
        String endpoint = s3config.endpoint;
        if (endpoint != null && !endpoint.isEmpty()) {
            scb = scb.endpointOverride(URI.create(s3config.endpoint));
        }
        scb = scb.credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(s3config.accessId, s3config.accessSecret)));
        scb = scb.forcePathStyle(Boolean.TRUE);
        return scb.build();
    }

    public static String normalizeObjectPath(String key) {
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

}
