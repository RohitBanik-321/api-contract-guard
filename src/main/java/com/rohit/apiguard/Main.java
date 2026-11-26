package com.rohit.apiguard;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class Main {

    record EndpointContract(String httpMethod, String fullPath, String returnType) {}

    record ApiSnapshot(
        Map<String, EndpointContract> endpoints,          // Key: "GET /api/orders/{id}"
        Map<String, Map<String, String>> dtoFields        // Key: "OrderResponse" -> Map<Field, Type>
    ) {}

    public static void main(String[] args) throws IOException {
        Path oldDir = Paths.get(args.length >= 2 ? args[0] : "samples/old");
        Path newDir = Paths.get(args.length >= 2 ? args[1] : "samples/new");

        System.out.println("Scanning OLD API version: " + oldDir);
        System.out.println("Scanning NEW API version: " + newDir);

        ApiSnapshot oldSnap = scanDirectory(oldDir);
        ApiSnapshot newSnap = scanDirectory(newDir);

        System.out.println("\n=================================================================");
        System.out.println(" API CONTRACT DIFF REPORT");
        System.out.println("=================================================================");

        int breakingCount = 0;

        // 1. Compare REST Endpoints
        for (Map.Entry<String, EndpointContract> entry : oldSnap.endpoints().entrySet()) {
            String key = entry.getKey();
            EndpointContract oldEp = entry.getValue();

            if (!newSnap.endpoints().containsKey(key)) {
                System.out.println("[BREAKING] Endpoint Removed: " + key + " (Returned: " + oldEp.returnType() + ")");
                breakingCount++;
            } else {
                EndpointContract newEp = newSnap.endpoints().get(key);
                if (!oldEp.returnType().equals(newEp.returnType())) {
                    System.out.println("[BREAKING] Endpoint Return Type Changed for " + key + ": "
                            + oldEp.returnType() + " -> " + newEp.returnType());
                    breakingCount++;
                }
            }
        }

        for (String key : newSnap.endpoints().keySet()) {
            if (!oldSnap.endpoints().containsKey(key)) {
                System.out.println("[SAFE]     Endpoint Added: " + key);
            }
        }

        // 2. Compare All DTO / Model Classes
        for (Map.Entry<String, Map<String, String>> classEntry : oldSnap.dtoFields().entrySet()) {
            String className = classEntry.getKey();
            Map<String, String> oldFields = classEntry.getValue();
            Map<String, String> newFields = newSnap.dtoFields().get(className);

            if (newFields == null) {
                System.out.println("[BREAKING] Payload Class Removed: " + className);
                breakingCount++;
                continue;
            }

            for (Map.Entry<String, String> field : oldFields.entrySet()) {
                String fieldName = field.getKey();
                String oldType = field.getValue();

                if (!newFields.containsKey(fieldName)) {
                    System.out.println("[BREAKING] " + className + "." + fieldName + " (" + oldType + ") was REMOVED.");
                    breakingCount++;
                } else if (!newFields.get(fieldName).equals(oldType)) {
                    System.out.println("[BREAKING] " + className + "." + fieldName + " type changed: "
                            + oldType + " -> " + newFields.get(fieldName));
                    breakingCount++;
                }
            }

            for (Map.Entry<String, String> field : newFields.entrySet()) {
                if (!oldFields.containsKey(field.getKey())) {
                    System.out.println("[SAFE]     " + className + "." + field.getKey()
                            + " (" + field.getValue() + ") was ADDED.");
                }
            }
        }

        System.out.println("=================================================================");
        if (breakingCount > 0) {
            System.out.println("RESULT: FAILED (" + breakingCount + " breaking changes detected).");
            System.exit(1);
        } else {
            System.out.println("RESULT: PASSED (Backward-compatible API).");
        }
    }

    private static ApiSnapshot scanDirectory(Path dir) throws IOException {
        Map<String, EndpointContract> endpoints = new LinkedHashMap<>();
        Map<String, Map<String, String>> dtoFields = new LinkedHashMap<>();

        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();

            for (Path file : javaFiles) {
                CompilationUnit cu = StaticJavaParser.parse(file);

                for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    boolean isController = cls.isAnnotationPresent("RestController") || cls.isAnnotationPresent("Controller");

                    if (isController) {
                        String basePath = extractMappingPath(cls.getAnnotationByName("RequestMapping").orElse(null));
                        extractControllerMethods(cls, basePath, endpoints);
                    } else {
                        Map<String, String> fields = new LinkedHashMap<>();
                        for (FieldDeclaration field : cls.findAll(FieldDeclaration.class)) {
                            String type = field.getElementType().asString();
                            field.getVariables().forEach(v -> fields.put(v.getNameAsString(), type));
                        }
                        if (!fields.isEmpty()) {
                            dtoFields.put(cls.getNameAsString(), fields);
                        }
                    }
                }
            }
        }
        return new ApiSnapshot(endpoints, dtoFields);
    }

    private static void extractControllerMethods(ClassOrInterfaceDeclaration cls, String basePath,
                                                 Map<String, EndpointContract> endpoints) {
        Map<String, String> mappingAnnotations = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
        );

        for (MethodDeclaration method : cls.getMethods()) {
            for (Map.Entry<String, String> entry : mappingAnnotations.entrySet()) {
                Optional<AnnotationExpr> ann = method.getAnnotationByName(entry.getKey());
                if (ann.isPresent()) {
                    String subPath = extractMappingPath(ann.get());
                    String fullPath = normalizePath(basePath + "/" + subPath);
                    String httpMethod = entry.getValue();
                    String returnType = method.getType().asString();
                    String key = httpMethod + " " + fullPath;

                    endpoints.put(key, new EndpointContract(httpMethod, fullPath, returnType));
                }
            }
        }
    }

    private static String extractMappingPath(AnnotationExpr ann) {
        if (ann == null) return "";
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return single.getMemberValue().toString().replace("\"", "");
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return "";
    }

    private static String normalizePath(String path) {
        String cleaned = path.replaceAll("/+", "/");
        if (cleaned.length() > 1 && cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.startsWith("/") ? cleaned : "/" + cleaned;
    }
}