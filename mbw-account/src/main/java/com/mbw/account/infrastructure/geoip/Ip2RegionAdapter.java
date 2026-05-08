package com.mbw.account.infrastructure.geoip;

import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.service.Ip2RegionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.lionsoul.ip2region.xdb.Searcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * lionsoul ip2region-backed implementation of {@link Ip2RegionService}
 * (device-management spec T8 / FR-011).
 *
 * <p>The .xdb v4 file (~10 MB, IPv4-only) is shipped on the classpath
 * at {@code geoip/ip2region.xdb} and pre-loaded into memory at
 * {@link PostConstruct} time. The {@link Searcher} returned by
 * {@code newWithBuffer} is thread-safe — list-endpoint requests share
 * a single instance.
 *
 * <p>The raw lookup result is formatted as
 * {@code "country|region|province|city|isp"}. We surface only mainland
 * China rows (country == "中国"); the province / city fields collapse
 * to a short label suitable for the UI ("上海" / "北京" / "广东深圳").
 * Non-China, IPv6, private / loopback, and any decoding failure all
 * fold to {@link Optional#empty()}.
 */
@Component
public class Ip2RegionAdapter implements Ip2RegionService {

    private static final Logger LOG = LoggerFactory.getLogger(Ip2RegionAdapter.class);

    private static final String DEFAULT_XDB_RESOURCE = "geoip/ip2region.xdb";
    private static final String COUNTRY_CN = "中国";
    private static final String SENTINEL_ZERO = "0";

    private final String xdbResourcePath;
    private Searcher searcher;

    public Ip2RegionAdapter(@Value("${mbw.geoip.xdb-resource:" + DEFAULT_XDB_RESOURCE + "}") String xdbResourcePath) {
        this.xdbResourcePath = xdbResourcePath;
    }

    @PostConstruct
    void load() throws IOException {
        Resource resource = new ClassPathResource(xdbResourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "ip2region.xdb missing on classpath at '" + xdbResourcePath + "'; check src/main/resources/geoip/");
        }
        byte[] buffer;
        try (InputStream in = resource.getInputStream()) {
            buffer = in.readAllBytes();
        }
        this.searcher = Searcher.newWithBuffer(buffer);
        LOG.info("ip2region adapter loaded {} bytes from classpath:{}", buffer.length, xdbResourcePath);
    }

    @PreDestroy
    void close() throws IOException {
        if (searcher != null) {
            searcher.close();
        }
    }

    @Override
    public Optional<String> resolve(IpAddress ip) {
        if (ip == null || ip.isPrivate()) {
            return Optional.empty();
        }
        // The shipped .xdb is the IPv4-only build — IPv6 callers must
        // fold to empty, not surface an ip2region error. The
        // Searcher.search(long) overload throws only IOException, side-
        // stepping the broader `throws Exception` declared on the
        // string overload (which would force a Checkstyle-illegal
        // catch (Exception) clause).
        Long ipv4 = parseIpv4(ip.value());
        if (ipv4 == null) {
            return Optional.empty();
        }
        try {
            String region = searcher.search(ipv4);
            return parseRegion(region);
        } catch (IOException ex) {
            LOG.warn("ip2region resolve failed for ip {}: {}", ip.value(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** Return the numeric value of an IPv4 dotted-quad literal, or {@code null} for IPv6 / malformed. */
    private static Long parseIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        long result = 0L;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    private static Optional<String> parseRegion(String region) {
        if (region == null || region.isBlank()) {
            return Optional.empty();
        }
        // The v4 .xdb emits at least 3 leading segments:
        // {@code country|province|city|...}. Subsequent segments (isp,
        // ISO country code) are ignored. We require province + city
        // present; the country must be 中国.
        String[] parts = region.split("\\|", -1);
        if (parts.length < 3) {
            return Optional.empty();
        }
        String country = parts[0];
        String province = parts[1];
        String city = parts[2];
        if (!COUNTRY_CN.equals(country)) {
            return Optional.empty();
        }
        if (SENTINEL_ZERO.equals(province) || SENTINEL_ZERO.equals(city)) {
            return Optional.empty();
        }
        String trimmedProvince = stripTrailingSuffix(province);
        String trimmedCity = stripTrailingSuffix(city);
        if (trimmedProvince.equals(trimmedCity)) {
            return Optional.of(trimmedCity);
        }
        return Optional.of(trimmedProvince + trimmedCity);
    }

    /** Strip trailing "省" / "市" / "自治区" / "特别行政区" so the UI label is compact. */
    private static String stripTrailingSuffix(String value) {
        if (value.endsWith("特别行政区")) {
            return value.substring(0, value.length() - 5);
        }
        if (value.endsWith("自治区")) {
            return value.substring(0, value.length() - 3);
        }
        if (value.endsWith("省") || value.endsWith("市")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
