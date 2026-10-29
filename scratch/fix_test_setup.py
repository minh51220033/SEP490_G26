with open('src/test/java/com/cvdcms/NanopixXrayRestControllerTest.java', 'r', encoding='utf-8') as f:
    text = f.read()

target = """    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);"""

rep = """    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new NanopixXrayRestController(nanopixXrayService);"""

text = text.replace(target, rep)
with open('src/test/java/com/cvdcms/NanopixXrayRestControllerTest.java', 'w', encoding='utf-8') as f:
    f.write(text)

print("UPDATED NanopixXrayRestControllerTest setUp")
