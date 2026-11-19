package com.cvdcms;

import com.cvdcms.DTO.MaterialCreateDTO;
import com.cvdcms.DTO.MaterialUpdateDTO;
import com.cvdcms.DTO.SupplierCreateDTO;
import com.cvdcms.DTO.SupplierUpdateDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InventoryDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private MaterialCreateDTO validMaterialCreateDto() {
        MaterialCreateDTO dto = new MaterialCreateDTO();
        dto.setMaterialCode("VT_001-A");
        dto.setMaterialName("Composite A2");
        dto.setCategory("Vật liệu trám");
        dto.setUnit("Ống");
        dto.setMinStockThreshold(new BigDecimal("10.000"));
        return dto;
    }

    private SupplierCreateDTO validSupplierCreateDto() {
        SupplierCreateDTO dto = new SupplierCreateDTO();
        dto.setSupplierCode("NCC-01_VN");
        dto.setSupplierName("Công ty Nha khoa ABC");
        dto.setTaxCode("0101234567");
        dto.setPhoneNumber("0901234567");
        dto.setAddress("Hà Nội");
        return dto;
    }

    @Test
    void materialCreateDto_ValidPayload_HasNoViolations() {
        assertTrue(validator.validate(validMaterialCreateDto()).isEmpty());
    }

    @Test
    void materialCreateDto_InvalidPatternCode_FailsValidation() {
        MaterialCreateDTO dto = validMaterialCreateDto();
        dto.setMaterialCode("VT 01!");
        Set<ConstraintViolation<MaterialCreateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("materialCode")));
    }

    @Test
    void materialCreateDto_NegativeMinStockThreshold_FailsValidation() {
        MaterialCreateDTO dto = validMaterialCreateDto();
        dto.setMinStockThreshold(new BigDecimal("-0.001"));
        Set<ConstraintViolation<MaterialCreateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("minStockThreshold")));
    }

    @Test
    void materialCreateDto_BlankRequiredFields_FailsAllRequiredFields() {
        MaterialCreateDTO dto = new MaterialCreateDTO();
        dto.setMaterialCode(" ");
        dto.setMaterialName("");
        dto.setCategory("");
        dto.setUnit("");
        dto.setMinStockThreshold(null);
        Set<ConstraintViolation<MaterialCreateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("materialCode")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("materialName")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("category")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("unit")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("minStockThreshold")));
    }

    @Test
    void materialUpdateDto_ValidPayload_HasNoViolations() {
        MaterialUpdateDTO dto = new MaterialUpdateDTO();
        dto.setMaterialCode("VT-02");
        dto.setMaterialName("Găng tay y tế");
        dto.setCategory("Tiêu hao");
        dto.setUnit("Hộp");
        dto.setMinStockThreshold(BigDecimal.ZERO);
        assertTrue(validator.validate(dto).isEmpty());
    }

    @Test
    void materialUpdateDto_OverMaxLengthFields_FailsSizeConstraint() {
        MaterialUpdateDTO dto = new MaterialUpdateDTO();
        dto.setMaterialCode("A".repeat(31));
        dto.setMaterialName("N".repeat(201));
        dto.setCategory("C".repeat(101));
        dto.setUnit("U".repeat(31));
        dto.setMinStockThreshold(BigDecimal.ONE);
        Set<ConstraintViolation<MaterialUpdateDTO>> violations = validator.validate(dto);
        assertEquals(4, violations.stream().map(v -> v.getPropertyPath().toString()).distinct().count());
    }

    @Test
    void supplierCreateDto_ValidPayloadWithBranchTaxCode_HasNoViolations() {
        SupplierCreateDTO dto = validSupplierCreateDto();
        dto.setTaxCode("0101234567-001");
        dto.setPhoneNumber("01234567890");
        assertTrue(validator.validate(dto).isEmpty());
    }

    @Test
    void supplierCreateDto_EmptyOptionalTaxCodeAndPhone_IsAllowed() {
        SupplierCreateDTO dto = validSupplierCreateDto();
        dto.setTaxCode("");
        dto.setPhoneNumber("");
        assertTrue(validator.validate(dto).isEmpty());
    }

    @Test
    void supplierCreateDto_InvalidTaxCodeFormat_FailsValidation() {
        SupplierCreateDTO dto = validSupplierCreateDto();
        dto.setTaxCode("12345");
        Set<ConstraintViolation<SupplierCreateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("taxCode")));
    }

    @Test
    void supplierCreateDto_InvalidPhoneFormat_FailsValidation() {
        SupplierCreateDTO dto = validSupplierCreateDto();
        dto.setPhoneNumber("84901234567");
        Set<ConstraintViolation<SupplierCreateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("phoneNumber")));
    }

    @Test
    void supplierCreateAndUpdateDto_Plus84PhoneFormat_HasNoViolations() {
        SupplierCreateDTO createDto = validSupplierCreateDto();
        createDto.setPhoneNumber("+84901234567");
        assertTrue(validator.validate(createDto).isEmpty());

        SupplierUpdateDTO updateDto = new SupplierUpdateDTO();
        updateDto.setSupplierCode("NCC_10");
        updateDto.setSupplierName("NCC Vật tư");
        updateDto.setTaxCode("0109999999");
        updateDto.setPhoneNumber("+84987654321");
        updateDto.setAddress("TP.HCM");
        assertTrue(validator.validate(updateDto).isEmpty());
    }

    @Test
    void supplierCreateDto_InvalidSupplierCodeChars_FailsValidation() {
        SupplierCreateDTO dto = validSupplierCreateDto();
        dto.setSupplierCode("NCC@01");
        Set<ConstraintViolation<SupplierCreateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("supplierCode")));
    }

    @Test
    void supplierUpdateDto_ValidPayload_HasNoViolations() {
        SupplierUpdateDTO dto = new SupplierUpdateDTO();
        dto.setSupplierCode("NCC_10");
        dto.setSupplierName("NCC Vật tư");
        dto.setTaxCode("0109999999");
        dto.setPhoneNumber("0987654321");
        dto.setAddress("TP.HCM");
        assertTrue(validator.validate(dto).isEmpty());
    }

    @Test
    void supplierUpdateDto_BlankCodeOrName_FailsValidation() {
        SupplierUpdateDTO dto = new SupplierUpdateDTO();
        dto.setSupplierCode("");
        dto.setSupplierName("   ");
        Set<ConstraintViolation<SupplierUpdateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("supplierCode")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("supplierName")));
    }

    @Test
    void supplierUpdateDto_AddressExceeding255Chars_FailsValidation() {
        SupplierUpdateDTO dto = new SupplierUpdateDTO();
        dto.setSupplierCode("NCC10");
        dto.setSupplierName("NCC");
        dto.setAddress("D".repeat(256));
        Set<ConstraintViolation<SupplierUpdateDTO>> violations = validator.validate(dto);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("address")));
    }
}
