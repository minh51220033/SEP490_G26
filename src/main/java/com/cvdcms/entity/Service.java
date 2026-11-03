package com.cvdcms.entity;

import com.cvdcms.entity.enums.ServiceStatus;
import com.cvdcms.entity.enums.ServiceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

@Entity
@Table(
        name = "services",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_service_code", columnNames = "service_code")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "service_id")
    private Long serviceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ServiceCategory category;

    @Column(name = "service_code", nullable = false, length = 30, unique = true)
    private String serviceCode;

    @Column(name = "service_name", nullable = false, length = 200)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 30)
    private ServiceType serviceType = ServiceType.PROCEDURE;

    @Column(name = "unit", nullable = false, length = 30)
    private String unit = "Lần";

    @Column(name = "base_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal basePrice = BigDecimal.ZERO;

    @Column(name = "surcharge_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal surchargeAmount = BigDecimal.ZERO;

    @Column(name = "requires_tooth", nullable = false)
    private Boolean requiresTooth = false;

    @Column(name = "is_paraclinical", nullable = false)
    private Boolean isParaclinical = false;

    @Column(name = "result_template_json", columnDefinition = "json")
    private String resultTemplateJson;

    @Column(name = "technical_requirements", columnDefinition = "TEXT")
    private String technicalRequirements;

    @Column(name = "is_bookable_online", nullable = false)
    private Boolean isBookableOnline = false;

    @Column(name = "warranty_months")
    private Integer warrantyMonths;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ServiceStatus status = ServiceStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Transient
    public String getSanitizedDescription() {
        if (this.description == null || this.description.isBlank()) {
            return null;
        }
        Safelist safelist = Safelist.relaxed()
                .addTags(
                        "table", "thead", "tbody", "tfoot", "tr", "th", "td",
                        "figure", "figcaption", "section", "article", "hr", "br", "s", "sub", "sup"
                )
                .addAttributes("img", "src", "alt", "title", "width", "height", "style", "class", "loading")
                .addAttributes("figure", "class", "style")
                .addAttributes("figcaption", "class", "style")
                .addAttributes("p", "class", "style")
                .addAttributes("h1", "class", "style")
                .addAttributes("h2", "class", "style")
                .addAttributes("h3", "class", "style")
                .addAttributes("h4", "class", "style")
                .addAttributes("div", "class", "style")
                .addAttributes("span", "class", "style")
                .addAttributes("table", "class", "style", "border", "cellpadding", "cellspacing")
                .addAttributes("td", "class", "style", "colspan", "rowspan")
                .addAttributes("th", "class", "style", "colspan", "rowspan")
                .addAttributes("a", "href", "title", "target", "rel", "class", "style")
                .preserveRelativeLinks(true);

        String cleaned = Jsoup.clean(this.description, "https://cvdcms.local", safelist);
        Document doc = Jsoup.parseBodyFragment(cleaned);
        for (Element el : doc.select("[style]")) {
            String style = el.attr("style");
            if (style != null && style.toLowerCase().matches(".*(expression\\s*\\(|javascript\\s*:|vbscript\\s*:|-moz-binding).*")) {
                el.removeAttr("style");
            }
        }
        for (Element el : doc.select("[href], [src]")) {
            for (String attr : new String[]{"href", "src"}) {
                if (el.hasAttr(attr)) {
                    String val = el.attr(attr).trim().toLowerCase();
                    if (val.startsWith("javascript:") || val.startsWith("vbscript:") || val.startsWith("data:text/html")) {
                        el.removeAttr(attr);
                    }
                }
            }
        }
        if (doc.text().replace(" ", "").isBlank() && doc.select("img").isEmpty()) {
            return null;
        }
        return doc.body().html();
    }
}
