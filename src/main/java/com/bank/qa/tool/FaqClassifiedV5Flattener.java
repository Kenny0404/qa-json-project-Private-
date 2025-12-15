package com.bank.qa.tool;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class FaqClassifiedV5Flattener {

    public static void main(String[] args) throws Exception {
        String inputResource = args.length >= 1 ? args[0] : "faq_classified_v5.json";
        String outputPath = args.length >= 2 ? args[1] : "src/main/resources/faq_from_classified_v5.json";

        ObjectMapper mapper = new ObjectMapper();

        JsonNode root;
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(inputResource)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found on classpath: " + inputResource);
            }
            root = mapper.readTree(is);
        }

        ArrayNode out = mapper.createArrayNode();

        List<String> path = new ArrayList<>();
        flattenNode(mapper, out, root, path, inputResource);

        Path outPath = Paths.get(outputPath);
        Path parent = outPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ObjectWriter writer = mapper.writer()
                .with(SerializationFeature.INDENT_OUTPUT)
                ;
        writer.writeValue(outPath.toFile(), out);

        System.out.println("Flattened FAQ written: " + out.size() + " items -> " + outPath.toAbsolutePath());
    }

    private static void flattenNode(ObjectMapper mapper, ArrayNode out, JsonNode node, List<String> path, String source)
            throws Exception {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isObject()) {
                    continue;
                }

                ObjectNode o = mapper.createObjectNode();

                int id = item.path("id").asInt(-1);
                String question = safeText(item.get("question"));
                String answer = safeText(item.get("answer"));

                o.put("id", id);
                o.put("question", cleanText(question));
                o.put("answer", cleanText(answer));

                if (item.has("askCount")) {
                    o.set("askCount", item.get("askCount"));
                }
                if (item.has("original_row")) {
                    o.set("original_row", item.get("original_row"));
                }

                String category = path.isEmpty() ? "" : path.get(0);
                String module = path.size() <= 1 ? "" : String.join(" > ", path.subList(1, path.size()));

                if (!category.isEmpty()) {
                    o.put("category", category);
                }
                if (!module.isEmpty()) {
                    o.put("module", module);
                }
                o.put("source", source);

                out.add(o);
            }
            return;
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode child = e.getValue();

                path.add(key);
                flattenNode(mapper, out, child, path, source);
                path.remove(path.size() - 1);
            }
        }
    }

    private static String safeText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    private static String cleanText(String s) {
        if (s == null) {
            return "";
        }

        String t = s.trim();

        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }

        t = t.replace("\\r\\n", "\n");

        t = t.replace("->>", " > ");
        t = t.replace(">>", " > ");
        t = t.replace("->", " > ");

        t = t.replace("\\\\", "\\");
        t = t.replace("\\", " > ");
        t = t.replace("/", " > ");

        t = t.replaceAll("[ \t\f]+", " ");
        t = t.replaceAll(" ?\\n ?", "\n");

        t = t.replaceAll("( ?> ?){2,}", " > ");

        return t.trim();
    }
}
