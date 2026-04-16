package org.puppylab.cryptodrive.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class JsonUtils {

    private static ObjectMapper objectMapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        var mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T fromJson(String data, Class<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T fromJson(byte[] data, Class<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> void fillJson(byte[] data, T bean) {
        try {
            objectMapper.readerForUpdating(bean).readValue(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeJson(Object obj, Writer write) {
        try {
            objectMapper.writeValue(write, obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeJson(Object obj, Path path) {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            objectMapper.writeValue(writer, obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T readJson(Path path, Class<T> clazz) {
        try (var input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return objectMapper.readValue(input, clazz);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
