package com.skyeye.mc_lang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Update {
  public static void main(String[] args) throws Exception {
    Path currentPath = Paths.get("").toAbsolutePath();
    Path langDirFull = currentPath.resolve("full");
    Files.createDirectories(langDirFull);

    ObjectMapper mapper = new ObjectMapper();
    HttpClient client = HttpClient.newHttpClient();

    // 下載 version_manifest_v2.json
    String manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(manifestUrl)).build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    JsonNode manifest = mapper.readTree(resp.body());

    // 取得版本
    String version = "1.13";
    Files.writeString(currentPath.resolve("version.txt"), version);

    JsonNode versionInfo = null;
    for (JsonNode v : manifest.get("versions")) {
      if (v.get("id").asText().equals(version)) {
        versionInfo = v;
        break;
      }
    }
    if (versionInfo == null) {
      System.out.println("Version not found.");
      return;
    }

    // 下載 client manifest
    String clientManifestUrl = versionInfo.get("url").asText();
    resp = client.send(HttpRequest.newBuilder().uri(URI.create(clientManifestUrl)).build(),
        HttpResponse.BodyHandlers.ofString());
    JsonNode clientManifest = mapper.readTree(resp.body());

    // 下載 asset index
    String assetIndexUrl = clientManifest.get("assetIndex").get("url").asText();
    resp = client.send(HttpRequest.newBuilder().uri(URI.create(assetIndexUrl)).build(),
        HttpResponse.BodyHandlers.ofString());
    JsonNode assetIndex = mapper.readTree(resp.body()).get("objects");

    // 下載 client.jar
    String clientJarUrl = clientManifest.get("downloads").get("client").get("url").asText();
    String clientSha1 = clientManifest.get("downloads").get("client").get("sha1").asText();
    Path clientJarPath = langDirFull.resolve("client.jar");
    downloadFile(clientJarUrl, clientJarPath);
    if (!sha1(clientJarPath).equalsIgnoreCase(clientSha1)) {
      System.out.println("client.jar SHA1 mismatch!");
      return;
    }

    // 解壓 en_us.json
    try (ZipFile zip = new ZipFile(clientJarPath.toFile())) {
      ZipEntry entry = zip.getEntry("assets/minecraft/lang/en_us.json");
      if (entry != null) {
        try (InputStream is = zip.getInputStream(entry)) {
          Files.copy(is, langDirFull.resolve("en_us.json"), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    Files.delete(clientJarPath);

    // 下載其他語言檔
    String[] langList = {
        "zh_cn", "zh_hk", "zh_tw", "lzh", "ja_jp", "ko_kr", "vi_vn", "de_de",
        "es_es", "fr_fr", "it_it", "nl_nl", "pt_br", "ru_ru", "th_th", "uk_ua"
    };
    for (String lang : langList) {
      String key = "minecraft/lang/" + lang + ".json";
      if (assetIndex.has(key)) {
        String hash = assetIndex.get(key).get("hash").asText();
        String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
        Path outPath = langDirFull.resolve(lang + ".json");
        downloadFile(url, outPath);
        if (!sha1(outPath).equalsIgnoreCase(hash)) {
          System.out.println(lang + ".json SHA1 mismatch!");
        }
      }
    }

    System.out.println("Done. Files are in 'full' directory.");
  }

  static void downloadFile(String url, Path out) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
    HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream is = resp.body()) {
      Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static String sha1(Path file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-1");
    try (InputStream is = Files.newInputStream(file)) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = is.read(buf)) > 0)
        md.update(buf, 0, n);
    }
    StringBuilder sb = new StringBuilder();
    for (byte b : md.digest())
      sb.append(String.format("%02x", b));
    return sb.toString();
  }
}