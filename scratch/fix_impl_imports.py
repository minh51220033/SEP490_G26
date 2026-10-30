with open('src/main/java/com/cvdcms/service/impl/NanopixXrayServiceImpl.java', 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Add import
target_imp = "import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;"
rep_imp = """import com.cvdcms.entity.enums.ParaclinicalFileType;
import com.cvdcms.entity.enums.ParaclinicalOrderItemStatus;"""

# 2. Fix method call
target_call = "NanopixXraySyncLog saved = nanoPixIntegrationService.ingestFromNanoPix(request, file, staffId);"
rep_call = "NanopixXraySyncLog saved = nanoPixIntegrationService.ingestXray(request, file, staffId);"

text = text.replace(target_imp, rep_imp)
text = text.replace(target_call, rep_call)

with open('src/main/java/com/cvdcms/service/impl/NanopixXrayServiceImpl.java', 'w', encoding='utf-8') as f:
    f.write(text)

print("FIXED NanopixXrayServiceImpl")
