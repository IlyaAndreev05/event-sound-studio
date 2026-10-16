package io.github.ilyaandreev05.eventsoundstudio.audio;

import javax.sound.sampled.Mixer;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MixerOutputSelector {
    private static final String JAVA_SOUND_PREFIX = "java-sound-v1|";
    private static final String ALSA_PREFIX = "alsa-v1|";
    private static final Pattern ALSA_PCM = Pattern.compile("(?:\\[|\\b)(?:plug)?hw:(\\d+),(\\d+)(?:\\]|\\b)");
    private static final Pattern ALSA_CARD = Pattern.compile("^\\s*(\\d+)\\s+\\[([^]]+)]\\s*:");

    private MixerOutputSelector() {}

    public static String forMixer(Mixer.Info info) {
        return forMixer(info.getName(), info.getVendor(), info.getDescription(),
                Path.of("/proc/asound/cards"), Path.of("/sys/class/sound"));
    }

    static String forMixer(String name, String vendor, String description, Path cardsFile, Path soundClass) {
        String safeName = value(name);
        String safeVendor = value(vendor);
        String safeDescription = value(description);
        Optional<AlsaAddress> address = parseAlsaAddress(safeName);
        if (address.isPresent()) {
            Optional<String> cardIdentity = stableAlsaCardIdentity(address.get().cardIndex(), cardsFile, soundClass);
            if (cardIdentity.isPresent()) {
                return ALSA_PREFIX + encode(cardIdentity.get()) + "|" + address.get().deviceIndex()
                        + "|" + encode(safeName) + "|" + encode(safeVendor) + "|" + encode(safeDescription);
            }
        }
        return JAVA_SOUND_PREFIX + encode(safeName) + "|" + encode(safeVendor) + "|" + encode(safeDescription);
    }

    public static Mixer.Info resolve(String selector, Mixer.Info[] mixers) {
        if (selector == null || selector.isBlank()) throw new IllegalArgumentException("Audio output selector is empty");
        List<Mixer.Info> matches = new ArrayList<>();
        for (Mixer.Info mixer : mixers) if (matches(selector, mixer)) matches.add(mixer);
        if (matches.isEmpty()) throw new IllegalArgumentException("Audio output not found: " + displayName(selector));
        if (matches.size() > 1) throw new IllegalArgumentException("Audio output selector is ambiguous: " + displayName(selector));
        return matches.getFirst();
    }

    public static boolean matches(String selector, Mixer.Info mixer) {
        return matches(selector, mixer.getName(), mixer.getVendor(), mixer.getDescription(),
                Path.of("/proc/asound/cards"), Path.of("/sys/class/sound"));
    }

    static boolean matches(String selector, String name, String vendor, String description, Path cardsFile, Path soundClass) {
        String selectorKey = canonicalKey(selector);
        String candidateKey = canonicalKey(forMixer(name, vendor, description, cardsFile, soundClass));
        if (selectorKey == null) return selector.equals(name);
        return selectorKey.equals(candidateKey);
    }

    public static boolean available(String selector, List<String> availableSelectors) {
        return availableSelectors.stream().filter(candidate -> equivalent(selector, candidate)).count() == 1;
    }

    public static boolean equivalent(String first, String second) {
        if (first.equals(second)) return true;
        String firstKey = canonicalKey(first);
        String secondKey = canonicalKey(second);
        if (firstKey != null && secondKey != null) return firstKey.equals(secondKey);
        if (firstKey == null) return first.equals(displayName(second));
        if (secondKey == null) return second.equals(displayName(first));
        return false;
    }

    public static String displayName(String selector) {
        Parsed parsed = parse(selector);
        return parsed == null ? selector : parsed.name();
    }

    static String canonicalKey(String selector) {
        Parsed parsed = parse(selector);
        if (parsed == null) return null;
        if (parsed.alsa()) return "alsa|" + parsed.fields().getFirst() + "|" + parsed.fields().get(1);
        return "java-sound|" + parsed.fields().getFirst() + "|" + parsed.fields().get(1) + "|" + parsed.fields().get(2);
    }

    static Optional<String> stableAlsaCardIdentity(int cardIndex, Path cardsFile, Path soundClass) {
        Path device = soundClass.resolve("card" + cardIndex).resolve("device");
        if (Files.exists(device)) {
            try { return Optional.of(device.toRealPath().toString()); }
            catch (IOException ignored) { }
        }
        if (!Files.isRegularFile(cardsFile)) return Optional.empty();
        try {
            for (String line : Files.readAllLines(cardsFile)) {
                Matcher matcher = ALSA_CARD.matcher(line);
                if (matcher.find() && Integer.parseInt(matcher.group(1)) == cardIndex)
                    return Optional.of("alsa-card-id:" + matcher.group(2).trim());
            }
        } catch (IOException | NumberFormatException ignored) { }
        return Optional.empty();
    }

    static Optional<AlsaAddress> parseAlsaAddress(String name) {
        Matcher matcher = ALSA_PCM.matcher(name);
        if (!matcher.find()) return Optional.empty();
        try { return Optional.of(new AlsaAddress(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)))); }
        catch (NumberFormatException ignored) { return Optional.empty(); }
    }

    static String identityHint(String selector) {
        Parsed parsed = parse(selector);
        if (parsed == null) return "";
        if (parsed.alsa()) {
            String path = parsed.fields().getFirst();
            String last = path.substring(path.lastIndexOf('/') + 1);
            String device = parsed.fields().get(1);
            return last + " · PCM " + device;
        }
        String vendor = parsed.fields().get(1);
        String description = parsed.fields().get(2);
        return !description.isBlank() ? description : vendor;
    }

    private static Parsed parse(String selector) {
        if (selector == null) return null;
        String[] fields = selector.split("\\|", -1);
        try {
            if (fields.length == 6 && fields[0].equals("alsa-v1")) {
                return new Parsed(true, List.of(decode(fields[1]), fields[2], decode(fields[3]), decode(fields[4]), decode(fields[5])));
            }
            if (fields.length == 4 && fields[0].equals("java-sound-v1")) {
                return new Parsed(false, List.of(decode(fields[1]), decode(fields[2]), decode(fields[3])));
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private static String value(String value) { return value == null ? "" : value.trim(); }

    private record Parsed(boolean alsa, List<String> fields) {
        String name() { return alsa ? fields.get(2) : fields.getFirst(); }
    }
    record AlsaAddress(int cardIndex, int deviceIndex) {}
}
