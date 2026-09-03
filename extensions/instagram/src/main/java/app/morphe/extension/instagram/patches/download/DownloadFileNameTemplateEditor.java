/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.download;

public final class DownloadFileNameTemplateEditor {
    private DownloadFileNameTemplateEditor() {
    }

    public static EditResult removePreviousComponent(
            String template,
            int selectionStart,
            int selectionEnd
    ) {
        String safeTemplate = template == null ? "" : template;
        if (selectionStart < 0 || selectionEnd < 0) {
            selectionStart = safeTemplate.length();
            selectionEnd = selectionStart;
        }
        int start = clamp(Math.min(selectionStart, selectionEnd), safeTemplate.length());
        int end = clamp(Math.max(selectionStart, selectionEnd), safeTemplate.length());

        if (start < end) {
            return removeRange(safeTemplate, start, end);
        }

        int tokenStart = safeTemplate.lastIndexOf('{', start - 1);
        int tokenEnd = tokenStart < 0 ? -1 : safeTemplate.indexOf('}', tokenStart);
        if (tokenEnd >= 0
                && start <= tokenEnd + 1
                && DownloadFileNameFormatter.Token.fromKey(
                        safeTemplate.substring(tokenStart + 1, tokenEnd)
                ) != null) {
            return removeRange(safeTemplate, tokenStart, tokenEnd + 1);
        }

        if (start > 0 && isSeparator(safeTemplate.charAt(start - 1))) {
            return removeRange(safeTemplate, start - 1, start);
        }

        int literalStart = start;
        while (literalStart > 0
                && !isSeparator(safeTemplate.charAt(literalStart - 1))
                && !tokenEndsAt(safeTemplate, literalStart)) {
            literalStart--;
        }
        int literalEnd = start;
        while (literalEnd < safeTemplate.length()
                && !isSeparator(safeTemplate.charAt(literalEnd))
                && !tokenStartsAt(safeTemplate, literalEnd)) {
            literalEnd++;
        }
        if (literalStart < start) {
            return removeRange(safeTemplate, literalStart, literalEnd);
        }

        return new EditResult(safeTemplate, start);
    }

    private static EditResult removeRange(String template, int start, int end) {
        return new EditResult(template.substring(0, start) + template.substring(end), start);
    }

    private static boolean tokenEndsAt(String template, int index) {
        if (template.charAt(index - 1) != '}') return false;
        int start = template.lastIndexOf('{', index - 2);
        return start >= 0 && DownloadFileNameFormatter.Token.fromKey(
                template.substring(start + 1, index - 1)
        ) != null;
    }

    private static boolean tokenStartsAt(String template, int index) {
        if (template.charAt(index) != '{') return false;
        int end = template.indexOf('}', index + 1);
        return end >= 0 && DownloadFileNameFormatter.Token.fromKey(
                template.substring(index + 1, end)
        ) != null;
    }

    private static boolean isSeparator(char character) {
        return DownloadFileNameFormatter.Separator.isSupported(character);
    }

    private static int clamp(int index, int length) {
        return Math.max(0, Math.min(index, length));
    }

    public static final class EditResult {
        public final String text;
        public final int cursor;

        private EditResult(String text, int cursor) {
            this.text = text;
            this.cursor = cursor;
        }
    }
}
