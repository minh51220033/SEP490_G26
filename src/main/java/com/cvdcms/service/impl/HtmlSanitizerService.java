package com.cvdcms.service.impl;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

@Service
public class HtmlSanitizerService {

    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        Safelist safelist = Safelist.relaxed()
                // 1. Cho phép thêm các thẻ mà CKEditor 5 và Bảng HTML hay dùng
                .addTags(
                        "table", "thead", "tbody", "tfoot", "tr", "th", "td",
                        "figure", "figcaption", "section", "article", "hr", "br"
                )
                // 2. Cho phép đầy đủ thuộc tính cho thẻ <img>
                .addAttributes("img", "src", "alt", "title", "width", "height", "style", "class", "loading")

                // 3. Cho phép class và style để giữ căn chỉnh (căn trái, căn giữa, kích thước) của CKEditor
                .addAttributes("figure", "class", "style")
                .addAttributes("figcaption", "class", "style")
                .addAttributes("p", "class", "style")
                .addAttributes("div", "class", "style")
                .addAttributes("span", "class", "style")
                .addAttributes("table", "class", "style", "border", "cellpadding", "cellspacing")
                .addAttributes("td", "class", "style", "colspan", "rowspan")
                .addAttributes("th", "class", "style", "colspan", "rowspan")
                .addAttributes("a", "href", "title", "target", "rel", "class", "style")

                // 4. QUAN TRỌNG NHẤT: Giữ lại đường dẫn nội bộ dạng /uploads/news/filename.jpg
                .preserveRelativeLinks(true);

        return Jsoup.clean(html, safelist);
    }
}