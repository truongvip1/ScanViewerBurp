package com.scanviewer;

public final class BindingRegexReview {
    private static void inspect(String name, String text, LoginMacroRegex.SelectionRule rule) {
        try {
            var match = LoginMacroRegex.extract(text, rule.expression(), rule.group());
            System.out.println(name + " | matched=" + match.matched() + " | value=" + match.value());
        } catch (RuntimeException error) { System.out.println(name + " | error=" + error.getMessage()); }
    }
    public static void main(String[] args) {
        String sample = "GET /api?q=123&b=123&c=45 HTTP/1.1\r\nHost: example.test\r\n\r\n";
        int start = sample.indexOf("&b=") + 3;
        var rule = LoginMacroRegex.fromSelection(sample, start, start + 3);
        System.out.println("QUERY_RULE | " + rule.expression());
        inspect("original", sample, rule);
        inspect("b changes alone", sample.replace("&b=123", "&b=XYZ987"), rule);
        inspect("q changes", sample.replace("?q=123", "?q=987"), rule);
        inspect("c changes", sample.replace("&c=45", "&c=987"), rule);
        inspect("extra parameter after b", sample.replace("&b=123", "&b=XYZ987&extra=9"), rule);
        System.out.println("REPLACEMENT | " + LoginMacroRegex.replaceSelection(
                sample.replace("&b=123", "&b=XYZ987&extra=9"), rule.expression(), rule.group(), "NEW")
                .replace("\r", "\\r").replace("\n", "\\n"));
        var partial = LoginMacroRegex.fromSelection(sample, start + 1, start + 3);
        System.out.println("PARTIAL_RULE | " + partial.expression());
        inspect("partial original", sample, partial);
        String response = "HTTP/1.1 200 OK\r\nX-Token: old\r\n\r\n";
        int token = response.indexOf("old");
        var tokenRule = LoginMacroRegex.fromSelection(response, token, token + 3);
        inspect("duplicated response header", "HTTP/1.1 200 OK\r\nX-Token: first\r\nX-Token: second\r\n\r\n", tokenRule);
        System.out.println("Completed local string probes; no HTTP requests sent.");
    }
}
