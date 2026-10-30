with open('src/main/java/com/cvdcms/controller/NanopixXrayRestController.java', 'r', encoding='utf-8') as f:
    text = f.read()

target = """    private User requireUser(HttpSession session) {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }
        User user = (User) session.getAttribute("user");
        if (user == null || user.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng tính năng này.");
        }
        return user;
    }"""

replacement = """    private User requireUser(HttpSession session) {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }
        Object loggedIn = session.getAttribute("loggedInUser");
        if (loggedIn instanceof User u && u.getUserId() != null) {
            return u;
        }
        Object userObj = session.getAttribute("user");
        if (userObj instanceof User u && u.getUserId() != null) {
            return u;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để sử dụng tính năng này.");
    }"""

if target in text:
    text = text.replace(target, replacement)
    with open('src/main/java/com/cvdcms/controller/NanopixXrayRestController.java', 'w', encoding='utf-8') as f:
        f.write(text)
    print("SUCCESS")
else:
    # Try normalizing newlines
    text_crlf = text.replace("\r\n", "\n")
    if target in text_crlf:
        text_crlf = text_crlf.replace(target, replacement)
        with open('src/main/java/com/cvdcms/controller/NanopixXrayRestController.java', 'w', encoding='utf-8') as f:
            f.write(text_crlf)
        print("SUCCESS_CRLF")
    else:
        print("NOT FOUND")
