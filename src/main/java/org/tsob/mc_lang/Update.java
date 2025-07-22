package org.tsob.mc_lang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Update {
  public static void main(String[] args) throws Exception {
    // 詢問使用者從哪個版本開始下載
    Scanner scanner = new Scanner(System.in);
    System.out.print("請輸入起始版本 (例如: 1.13): ");
    String startVersion = scanner.nextLine().trim();
    if (startVersion.isEmpty()) {
      startVersion = "1.13"; // 預設值
    }
    System.out.println("將從版本 " + startVersion + " 開始下載");

    // 取得目前工作目錄作為語言包存放根目錄
    Path currentPath = Paths.get("").toAbsolutePath();
    ObjectMapper mapper = new ObjectMapper();
    HttpClient client = HttpClient.newHttpClient();

    // 下載 Minecraft 版本資訊清單
    String manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(manifestUrl)).build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    JsonNode manifest = mapper.readTree(resp.body());

    // 取得所有版本，並反轉順序（由舊到新）
    java.util.List<JsonNode> versions = new java.util.ArrayList<>();
    manifest.get("versions").forEach(versions::add);
    java.util.Collections.reverse(versions); // 反轉，變成舊到新

    boolean startCollect = false;
    for (JsonNode v : versions) {
      String version = v.get("id").asText();
      // 只處理 release 版本，且 >= 使用者指定版本
      if (version.equals(startVersion))
        startCollect = true;
      if (!startCollect)
        continue;
      if (!v.get("type").asText().equals("release"))
        continue;

      System.out.println("處理版本: " + version);

      // 為每個版本建立一個資料夾，語言包將存放於此
      Path baseDir = currentPath.resolve("Local Language Pack");
      Path langDir = baseDir.resolve(version);
      Files.createDirectories(langDir);

      // 下載該版本的 client manifest
      String clientManifestUrl = v.get("url").asText();
      resp = client.send(HttpRequest.newBuilder().uri(URI.create(clientManifestUrl)).build(),
          HttpResponse.BodyHandlers.ofString());
      JsonNode clientManifest = mapper.readTree(resp.body());

      // 下載 asset index，取得語言檔案 hash
      String assetIndexUrl = clientManifest.get("assetIndex").get("url").asText();
      resp = client.send(HttpRequest.newBuilder().uri(URI.create(assetIndexUrl)).build(),
          HttpResponse.BodyHandlers.ofString());
      JsonNode assetIndex = mapper.readTree(resp.body()).get("objects");

      // 下載 client.jar，準備解壓 en_us.json
      String clientJarUrl = clientManifest.get("downloads").get("client").get("url").asText();
      String clientSha1 = clientManifest.get("downloads").get("client").get("sha1").asText();
      Path clientJarPath = langDir.resolve("client.jar");
      downloadFile(clientJarUrl, clientJarPath);
      // 驗證 client.jar 的 SHA1
      if (!sha1(clientJarPath).equalsIgnoreCase(clientSha1)) {
        System.out.println("client.jar SHA1 mismatch! " + version);
        Files.deleteIfExists(clientJarPath);
        continue;
      }

      // 從 client.jar 解壓 en_us.json 語言檔
      try (ZipFile zip = new ZipFile(clientJarPath.toFile())) {
        ZipEntry entry = zip.getEntry("assets/minecraft/lang/en_us.json");
        if (entry != null) {
          try (InputStream is = zip.getInputStream(entry)) {
            Files.copy(is, langDir.resolve("en_us.json"), StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
      // 刪除 client.jar，釋放空間
      Files.delete(clientJarPath);

      // // 下載其他語言檔案
      // String[] langList = {
      //     "zh_cn", "zh_hk", "zh_tw", "lzh", "ja_jp", "ko_kr", "vi_vn", "de_de",
      //     "es_es", "fr_fr", "it_it", "nl_nl", "pt_br", "ru_ru", "th_th", "uk_ua"
      // };
      // for (String lang : langList) {
      //   String key = "minecraft/lang/" + lang + ".json";
      //   if (assetIndex.has(key)) {
      //     String hash = assetIndex.get(key).get("hash").asText();
      //     // 組合語言檔案下載網址
      //     String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
      //     Path outPath = langDir.resolve(lang + ".json");
      //     downloadFile(url, outPath);
      //     // 驗證語言檔案 SHA1
      //     if (!sha1(outPath).equalsIgnoreCase(hash)) {
      //       System.out.println(lang + ".json SHA1 mismatch! " + version);
      //     }
      //   }
      // }

      // 下載所有語言檔案
      for (java.util.Iterator<String> it = assetIndex.fieldNames(); it.hasNext(); ) {
        String key = it.next();
        if (key.startsWith("minecraft/lang/") && key.endsWith(".json")) {
          String lang = key.substring("minecraft/lang/".length(), key.length() - ".json".length());
          String hash = assetIndex.get(key).get("hash").asText();
          // 組合語言檔案下載網址
          String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
          Path outPath = langDir.resolve(lang + ".json");
          // 檢查檔案是否已存在，存在就跳過下載
          if (Files.exists(outPath)) {
            System.out.println(lang + ".json 已存在，跳過下載 (" + version + ")");
            continue;
          }
          downloadFile(url, outPath);
          // 驗證語言檔案 SHA1
          if (!sha1(outPath).equalsIgnoreCase(hash)) {
            System.out.println(lang + ".json SHA1 mismatch! " + version);
          }
        }
      }
    }
    scanner.close();
    System.out.println("Done. Files are in version folders.");
  }

  // 下載檔案到指定路徑
  static void downloadFile(String url, Path out) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
    HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream is = resp.body()) {
      Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // 計算檔案 SHA1 值
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