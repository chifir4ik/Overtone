package dev.musicplayer.lyrics;

import dev.musicplayer.model.LyricLine;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Разбор пословного TTML из AMLL TTML DB. */
final class TtmlParser {
    private static final String TTM = "http://www.w3.org/ns/ttml#metadata";
    private static final int CJK_GROUP = 4;

    /** Пробелы между словами; в старых файлах базы их нет совсем. */
    private static final Pattern SPACING = Pattern.compile("</span>\\s+<span|<span[^>]*>\\s|\\s</span>");

    private TtmlParser() {}

    private record Piece(long ms, String text, boolean spaceBefore) {}

    static List<LyricLine> parse(String xml) {
        List<LyricLine> out = new ArrayList<>();
        boolean legacy = !SPACING.matcher(xml).find();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (ParserConfigurationException e) {
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            }
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList ps = doc.getElementsByTagNameNS("*", "p");
            for (int i = 0; i < ps.getLength(); i++) {
                LyricLine l = line((Element) ps.item(i), legacy);
                if (l != null) out.add(l);
            }
        } catch (Exception e) {
            return List.of();
        }
        out.sort(Comparator.comparingLong(LyricLine::timeMs));
        return out;
    }

    private static LyricLine line(Element p, boolean legacy) {
        long begin = time(p.getAttribute("begin"));
        List<Piece> pieces = new ArrayList<>();
        walk(p, pieces, new boolean[1], begin);

        if (pieces.isEmpty()) {
            String text = ownText(p);
            return begin < 0 || text.isEmpty() ? null : new LyricLine(begin, text);
        }
        pieces.sort(Comparator.comparingLong(Piece::ms));
        if (begin < 0) begin = pieces.get(0).ms();
        if (legacy) pieces = spaced(pieces);

        List<LyricLine.Word> words = new ArrayList<>();
        StringBuilder text = new StringBuilder(), word = null;
        long wordMs = 0;
        int len = 0;
        for (Piece pc : pieces) {
            if (pc.spaceBefore() && text.length() > 0) text.append(' ');
            text.append(pc.text());
            int n = pc.text().codePointCount(0, pc.text().length());
            boolean glue = word != null && !pc.spaceBefore()
                    && !(len >= CJK_GROUP && cjk(word.codePointBefore(word.length())) && cjk(pc.text().codePointAt(0)));
            if (glue) {
                word.append(pc.text());
                len += n;
            } else {
                if (word != null) words.add(new LyricLine.Word(Math.max(begin, wordMs), word.toString()));
                word = new StringBuilder(pc.text());
                wordMs = pc.ms();
                len = n;
            }
        }
        words.add(new LyricLine.Word(Math.max(begin, wordMs), word.toString()));
        return new LyricLine(begin, text.toString(), words);
    }

    /** Переводы и транслитерацию пропускаем, вложенные span (фоновые голоса) разворачиваем. */
    private static void walk(Node parent, List<Piece> out, boolean[] gap, long fallback) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.TEXT_NODE) {
                if (c.getTextContent().isBlank()) gap[0] = true;
                continue;
            }
            if (!(c instanceof Element e)) continue;
            String role = e.getAttributeNS(TTM, "role");
            if (role.equals("x-translation") || role.equals("x-roman")) continue;
            if (hasChildElements(e)) {
                gap[0] = true;
                walk(e, out, gap, fallback);
                gap[0] = true;
                continue;
            }
            String s = e.getTextContent();
            if (s.isBlank()) {
                gap[0] = true;
                continue;
            }
            long t = time(e.getAttribute("begin"));
            if (t < 0) t = fallback;
            boolean space = gap[0] || Character.isWhitespace(s.charAt(0));
            for (String part : s.strip().split("\\s+")) {
                out.add(new Piece(t, part, space));
                space = true;
            }
            gap[0] = Character.isWhitespace(s.charAt(s.length() - 1));
        }
    }

    /** Для старых файлов без пробелов: ставим их сами, кроме апострофов и знаков препинания. */
    private static List<Piece> spaced(List<Piece> in) {
        if (in.size() < 2 || cjk(in.get(0).text().codePointAt(0))) return in;
        List<Piece> out = new ArrayList<>(in.size());
        out.add(in.get(0));
        for (int i = 1; i < in.size(); i++) {
            Piece p = in.get(i);
            String prev = in.get(i - 1).text();
            boolean attach = ",.!?;:)'’".indexOf(p.text().charAt(0)) >= 0
                    || "'’(".indexOf(prev.charAt(prev.length() - 1)) >= 0;
            out.add(new Piece(p.ms(), p.text(), !attach));
        }
        return out;
    }

    private static boolean hasChildElements(Element e) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) return true;
        return false;
    }

    private static String ownText(Element p) {
        StringBuilder sb = new StringBuilder();
        for (Node c = p.getFirstChild(); c != null; c = c.getNextSibling())
            if (c.getNodeType() == Node.TEXT_NODE) sb.append(c.getTextContent());
        return sb.toString().strip();
    }

    private static boolean cjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL;
    }

    /** 00:12.345, 1:02:03.5, 12.5s, 300ms; -1 если не разобралось. */
    static long time(String s) {
        if (s == null) return -1;
        s = s.strip();
        try {
            if (s.endsWith("ms")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 2)));
            if (s.endsWith("s")) return Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
            String[] parts = s.split(":");
            double sec = 0;
            for (String part : parts) sec = sec * 60 + Double.parseDouble(part);
            return Math.round(sec * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
