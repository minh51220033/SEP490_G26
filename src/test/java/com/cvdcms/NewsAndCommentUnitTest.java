package com.cvdcms;

import com.cvdcms.DTO.CommentCreateDTO;
import com.cvdcms.DTO.NewsCreateDTO;
import com.cvdcms.controller.*;
import com.cvdcms.entity.*;
import com.cvdcms.repository.*;
import com.cvdcms.service.SystemAuditLogService;
import com.cvdcms.service.impl.HtmlSanitizerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NewsAndCommentUnitTest {

    @Mock
    private NewsArticleRepository newsRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private CommentLikeRepository commentLikeRepository;
    @Mock
    private CommentBanRepository commentBanRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PatientProfileRepository patientProfileRepository;
    @Mock
    private NewsArticleLikeRepository newsArticleLikeRepository;
    @Mock
    private SystemAuditLogService auditLogService;
    @Mock
    private HtmlSanitizerService htmlSanitizerService;

    private NewsController newsController;
    private CommentController commentController;
    private AdminCommentController adminCommentController;
    private AdminBanController adminBanController;
    private AdminNewsController adminNewsController;

    private User adminUser;
    private User normalUser;
    private Branch testBranch;
    private NewsArticle testNews;
    private Comment testComment;

    @BeforeEach
    void setUp() {
        newsController = new NewsController(
                newsRepository, commentRepository, commentLikeRepository,
                commentBanRepository, branchRepository, userRepository,
                patientProfileRepository, newsArticleLikeRepository
        );

        commentController = new CommentController(
                commentRepository, commentLikeRepository,
                commentBanRepository, newsRepository
        );

        adminCommentController = new AdminCommentController(
                commentRepository, commentBanRepository,
                userRepository, newsRepository, auditLogService
        );

        adminBanController = new AdminBanController(
                commentBanRepository, userRepository, auditLogService
        );

        adminNewsController = new AdminNewsController(
                newsRepository, htmlSanitizerService, commentRepository,
                branchRepository, auditLogService
        );

        Role adminRole = new Role();
        adminRole.setRoleName("ADMIN");

        Role userRole = new Role();
        userRole.setRoleName("PATIENT");

        testBranch = new Branch();
        testBranch.setBranchId(1L);
        testBranch.setBranchName("Cơ sở Cường Việt");
        testBranch.setIsActive(true);

        adminUser = new User();
        adminUser.setUserId(1L);
        adminUser.setUsername("admin");
        adminUser.setBranch(testBranch);
        adminUser.setRoles(Set.of(adminRole));

        normalUser = new User();
        normalUser.setUserId(2L);
        normalUser.setUsername("patient01");
        normalUser.setRoles(Set.of(userRole));

        testNews = new NewsArticle();
        testNews.setNewsId(10L);
        testNews.setTitle("Khám răng miễn phí tháng 11");
        testNews.setSlug("kham-rang-mien-phi-thang-11");
        testNews.setStatus("PUBLISHED");
        testNews.setBranch(testBranch);

        testComment = new Comment();
        testComment.setCommentId(100L);
        testComment.setNews(testNews);
        testComment.setUser(normalUser);
        testComment.setContent("Bình luận nha khoa hữu ích");
        testComment.setStatus("ACTIVE");
    }

    // =========================================================================
    // FT37: QUẢN LÝ TIN TỨC (PUBLIC & ADMIN)
    // =========================================================================

    @Test
    @DisplayName("FT37 - Khách vãng lai (chưa login) vẫn được xem danh sách tin tức công khai")
    void testPublicNewsList_GuestCanViewNews() {
        MockHttpSession session = new MockHttpSession();
        Model model = new ConcurrentModel();

        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(testBranch));
        when(newsRepository.searchPublishedByBranch(anyString(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(testNews)));

        String view = newsController.publicNewsList("", null, 0, 6, session, model);

        assertEquals("news/list", view);
        assertNull(model.getAttribute("errorMessage"));
        assertEquals("Tất cả cơ sở", model.getAttribute("currentBranchName"));
        assertNotNull(model.getAttribute("newsPage"));
    }

    @Test
    @DisplayName("FT37 - Admin/Bác sĩ không có hồ sơ bệnh nhân vẫn xem được tin tức, không bị báo lỗi")
    void testPublicNewsList_AdminWithoutPatientProfile_DoesNotThrowError() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        Model model = new ConcurrentModel();

        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser));
        when(patientProfileRepository.findFirstByUser_UserIdAndIsDefaultTrue(1L)).thenReturn(Optional.empty());
        when(branchRepository.findByIsActiveTrueOrderByBranchNameAsc()).thenReturn(List.of(testBranch));
        when(newsRepository.searchPublishedByBranch(anyString(), eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(testNews)));

        String view = newsController.publicNewsList("", null, 0, 6, session, model);

        assertEquals("news/list", view);
        assertNull(model.getAttribute("errorMessage"));
        assertEquals("Cơ sở Cường Việt", model.getAttribute("currentBranchName"));
    }

    @Test
    @DisplayName("FT37 - Admin tạo bài viết mới và hệ thống ghi nhận Audit Log (FT31)")
    void testAdminNewsCreate_SavesArticleAndLogsAudit() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ConcurrentModel();

        NewsCreateDTO dto = new NewsCreateDTO();
        dto.setTitle("Bài viết kiểm toán mới");
        dto.setContent("<p>Nội dung</p>");
        dto.setBranchId(1L);
        dto.setStatus("PUBLISHED");

        BindingResult bindingResult = new BeanPropertyBindingResult(dto, "newsCreateDTO");

        when(branchRepository.findById(1L)).thenReturn(Optional.of(testBranch));
        when(htmlSanitizerService.sanitize(anyString())).thenReturn("<p>Nội dung</p>");
        when(newsRepository.findBySlug(anyString())).thenReturn(Optional.empty());

        String view = adminNewsController.createNews(dto, bindingResult, request, session, model);

        assertEquals("redirect:/admin/news", view);
        verify(newsRepository).save(any(NewsArticle.class));
        verify(auditLogService).logAction(eq(adminUser), eq(testBranch), eq("CREATE_NEWS"), eq("news_articles"), any(), anyString(), isNull(), any());
    }

    @Test
    @DisplayName("FT37 - Admin xóa bài viết tin tức: Xóa cascade comment và ghi nhận Audit Log (FT31)")
    void testAdminNewsDelete_DeletesArticleAndLogsAudit() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(newsRepository.findById(10L)).thenReturn(Optional.of(testNews));

        String view = adminNewsController.deleteNews(10L, request, session);

        assertEquals("redirect:/admin/news", view);
        verify(commentRepository).deleteByNews_NewsId(10L);
        verify(newsRepository).delete(testNews);
        verify(auditLogService).logAction(eq(adminUser), eq(testBranch), eq("DELETE_NEWS"), eq("news_articles"), eq(10L), anyString(), any(), isNull());
    }

    // =========================================================================
    // FT38: BÌNH LUẬN, PHẢN HỒI & LIKE
    // =========================================================================

    @Test
    @DisplayName("FT38 - Tải chi tiết bài viết: Bảo toàn comment cha bị xóa nếu có câu trả lời bên dưới")
    void testNewsDetail_PreservesDeletedParentWithReplies() {
        MockHttpSession session = new MockHttpSession();
        Model model = new ConcurrentModel();

        Comment deletedParent = new Comment();
        deletedParent.setCommentId(200L);
        deletedParent.setNews(testNews);
        deletedParent.setStatus("DELETED");

        Comment activeReply = new Comment();
        activeReply.setCommentId(201L);
        activeReply.setNews(testNews);
        activeReply.setParentComment(deletedParent);
        activeReply.setStatus("ACTIVE");

        when(newsRepository.findBySlug("kham-rang-mien-phi-thang-11")).thenReturn(Optional.of(testNews));
        when(newsArticleLikeRepository.countByNews_NewsId(10L)).thenReturn(5L);
        when(commentRepository.findByNews_NewsIdAndParentCommentIsNull(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deletedParent)));
        when(commentRepository.findByParentComment_CommentIdInOrderByCreatedAtAsc(List.of(200L)))
                .thenReturn(List.of(activeReply));
        when(commentLikeRepository.countLikesGroupedByCommentIds(anyList())).thenReturn(Collections.emptyList());

        String view = newsController.newsDetail("kham-rang-mien-phi-thang-11", 0, session, model);

        assertEquals("news/detail", view);
        List<Comment> comments = (List<Comment>) model.getAttribute("comments");
        assertNotNull(comments);
        assertEquals(1, comments.size(), "Comment cha bị xóa vẫn được giữ lại để hiển thị replies con");
        assertEquals("DELETED", comments.get(0).getStatus());
    }

    @Test
    @DisplayName("FT38 - Người dùng bấm Thích bài viết tin tức (SCR-38.3)")
    void testToggleArticleLike_Success() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", normalUser);

        when(newsRepository.findById(10L)).thenReturn(Optional.of(testNews));
        when(commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(2L)).thenReturn(Optional.empty());
        when(newsArticleLikeRepository.findByNews_NewsIdAndUser_UserId(10L, 2L)).thenReturn(Optional.empty());

        String view = newsController.toggleArticleLike(10L, session);

        assertEquals("redirect:/news/kham-rang-mien-phi-thang-11", view);
        verify(newsArticleLikeRepository).save(any(NewsArticleLike.class));
    }

    @Test
    @DisplayName("FT38 - Tài khoản bị Ban thì không được Like bài viết")
    void testToggleArticleLike_BannedUser_Blocked() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", normalUser);

        CommentBan activeBan = new CommentBan();
        activeBan.setActive(true);
        activeBan.setExpiresAt(null);

        when(newsRepository.findById(10L)).thenReturn(Optional.of(testNews));
        when(commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(2L)).thenReturn(Optional.of(activeBan));

        String view = newsController.toggleArticleLike(10L, session);

        assertEquals("redirect:/news/kham-rang-mien-phi-thang-11", view);
        verify(newsArticleLikeRepository, never()).save(any());
        assertNotNull(session.getAttribute("commentError"));
    }

    @Test
    @DisplayName("FT38 - Tài khoản bị Ban thì không được Like bình luận")
    void testToggleCommentLike_BannedUser_Blocked() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", normalUser);

        CommentBan activeBan = new CommentBan();
        activeBan.setActive(true);
        activeBan.setExpiresAt(null);

        when(commentRepository.findById(100L)).thenReturn(Optional.of(testComment));
        when(commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(2L)).thenReturn(Optional.of(activeBan));

        String view = commentController.toggleLike(100L, session);

        assertEquals("redirect:/news/kham-rang-mien-phi-thang-11", view);
        verify(commentLikeRepository, never()).save(any());
        assertNotNull(session.getAttribute("commentError"));
    }

    @Test
    @DisplayName("FT38 - Không cho phép phản hồi vào một bình luận cha đã bị xóa")
    void testReplyComment_ToDeletedParent_Blocked() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", normalUser);

        Comment deletedComment = new Comment();
        deletedComment.setCommentId(105L);
        deletedComment.setNews(testNews);
        deletedComment.setStatus("DELETED");

        CommentCreateDTO dto = new CommentCreateDTO();
        dto.setContent("Phản hồi vào comment đã xóa");
        BindingResult bindingResult = new BeanPropertyBindingResult(dto, "commentCreateDTO");

        when(commentRepository.findById(105L)).thenReturn(Optional.of(deletedComment));

        String view = commentController.reply(105L, dto, bindingResult, session);

        assertEquals("redirect:/news/kham-rang-mien-phi-thang-11", view);
        verify(commentRepository, never()).save(any());
        assertEquals("Không thể phản hồi bình luận đã bị xóa.", session.getAttribute("commentError"));
    }

    // =========================================================================
    // FT39: KIỂM DUYỆT BÌNH LUẬN & QUẢN LÝ CẤM (SCR-39.1 ĐẾN SCR-39.4)
    // =========================================================================

    @Test
    @DisplayName("FT39 - Màn hình danh sách bình luận kiểm duyệt (SCR-39.1) trả về đúng thống kê")
    void testAdminListComments_Moderation_Success() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        Model model = new ConcurrentModel();

        when(commentRepository.searchCommentsAdmin(anyString(), anyString(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(testComment)));
        when(commentRepository.count()).thenReturn(10L);
        when(commentRepository.countByStatus("ACTIVE")).thenReturn(8L);
        when(commentRepository.countByStatus("DELETED")).thenReturn(2L);

        String view = adminCommentController.listComments("", "", null, 0, 15, session, model);

        assertEquals("admin/comments/list", view);
        assertEquals(10L, model.getAttribute("totalComments"));
        assertEquals(8L, model.getAttribute("activeComments"));
        assertEquals(2L, model.getAttribute("deletedComments"));
    }

    @Test
    @DisplayName("FT39 - Admin xóa bình luận vi phạm và ghi nhận Audit Log (SCR-39.2)")
    void testAdminDeleteComment_SoftDeletesAndLogsAudit() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        when(commentRepository.findById(100L)).thenReturn(Optional.of(testComment));

        String view = adminCommentController.deleteComment(100L, "/admin/comments", request, session, redirectAttributes);

        assertEquals("redirect:/admin/comments", view);
        assertEquals("DELETED", testComment.getStatus());
        verify(commentRepository).save(testComment);
        verify(auditLogService).logAction(eq(adminUser), eq(testBranch), eq("DELETE_COMMENT"), eq("comments"), eq(100L), anyString(), any(), any());
    }

    @Test
    @DisplayName("FT39 - Admin khôi phục bình luận đã xóa và ghi nhận Audit Log (SCR-39.2)")
    void testAdminRestoreComment_RestoresToActiveAndLogsAudit() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        testComment.setStatus("DELETED");
        when(commentRepository.findById(100L)).thenReturn(Optional.of(testComment));

        String view = adminCommentController.restoreComment(100L, "/admin/comments", request, session, redirectAttributes);

        assertEquals("redirect:/admin/comments", view);
        assertEquals("ACTIVE", testComment.getStatus());
        verify(commentRepository).save(testComment);
        verify(auditLogService).logAction(eq(adminUser), eq(testBranch), eq("RESTORE_COMMENT"), eq("comments"), eq(100L), anyString(), any(), any());
    }

    @Test
    @DisplayName("FT39 - Admin cấm người dùng bình luận có thời hạn (SCR-39.4)")
    void testAdminBanUser_CreatesBan_HidesComment_LogsAudit() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        when(commentRepository.findById(100L)).thenReturn(Optional.of(testComment));
        when(commentBanRepository.findFirstByUser_UserIdAndActiveTrueOrderByBannedAtDesc(2L)).thenReturn(Optional.empty());

        String view = adminCommentController.banUser(100L, "Spam quảng cáo", 7, "/admin/comments", request, session, redirectAttributes);

        assertEquals("redirect:/admin/comments", view);
        assertEquals("DELETED", testComment.getStatus(), "Bình luận vi phạm cũng được tự động ẩn");
        verify(commentBanRepository).save(argThat(ban ->
                ban.getUser().getUserId().equals(2L)
                        && ban.getActive()
                        && "Spam quảng cáo".equals(ban.getReason())
                        && ban.getExpiresAt() != null
        ));
        verify(auditLogService).logAction(eq(adminUser), eq(testBranch), eq("BAN_USER_COMMENT"), eq("comment_bans"), any(), anyString(), isNull(), any());
    }

    @Test
    @DisplayName("FT39 - Admin gỡ cấm bình luận thành công và ghi nhận Audit Log (SCR-39.4)")
    void testAdminUnbanUser_DeactivatesBanAndLogsAudit() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUser", adminUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        CommentBan ban = new CommentBan();
        ban.setBanId(50L);
        ban.setUser(normalUser);
        ban.setActive(true);

        when(commentBanRepository.findById(50L)).thenReturn(Optional.of(ban));

        String view = adminBanController.unbanUser(50L, request, session, redirectAttributes);

        assertEquals("redirect:/admin/bans", view);
        assertFalse(ban.getActive());
        verify(commentBanRepository).save(ban);
        verify(auditLogService).logAction(eq(adminUser), any(), eq("UNBAN_USER_COMMENT"), eq("comment_bans"), eq(50L), anyString(), any(), any());
        assertTrue(redirectAttributes.getFlashAttributes().containsKey("banSuccess"));
    }
}

