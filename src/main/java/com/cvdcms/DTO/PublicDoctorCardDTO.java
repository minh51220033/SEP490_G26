package com.cvdcms.DTO;

import com.cvdcms.entity.Branch;
import com.cvdcms.entity.StaffSchedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PublicDoctorCardDTO {

    private Long staffId;
    private String staffCode;
    private String fullName;
    private String initials;
    private String genderLabel;
    private String positionTitle;
    private String specialty;
    private String department;
    private String practiceLicenseNo;
    private LocalDate hireDate;
    private String primaryBranchName;
    private Long primaryBranchId;
    private List<String> branchNames = new ArrayList<>();
    private List<Branch> activeBranches = new ArrayList<>();
    private List<StaffSchedule> upcomingSchedules = new ArrayList<>();

    public PublicDoctorCardDTO() {
    }

    public Long getStaffId() {
        return staffId;
    }

    public void setStaffId(Long staffId) {
        this.staffId = staffId;
    }

    public String getStaffCode() {
        return staffCode;
    }

    public void setStaffCode(String staffCode) {
        this.staffCode = staffCode;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getInitials() {
        return initials;
    }

    public void setInitials(String initials) {
        this.initials = initials;
    }

    public String getGenderLabel() {
        return genderLabel;
    }

    public void setGenderLabel(String genderLabel) {
        this.genderLabel = genderLabel;
    }

    public String getPositionTitle() {
        return positionTitle;
    }

    public void setPositionTitle(String positionTitle) {
        this.positionTitle = positionTitle;
    }

    public String getSpecialty() {
        return specialty;
    }

    public void setSpecialty(String specialty) {
        this.specialty = specialty;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getPracticeLicenseNo() {
        return practiceLicenseNo;
    }

    public void setPracticeLicenseNo(String practiceLicenseNo) {
        this.practiceLicenseNo = practiceLicenseNo;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
    }

    public String getPrimaryBranchName() {
        return primaryBranchName;
    }

    public void setPrimaryBranchName(String primaryBranchName) {
        this.primaryBranchName = primaryBranchName;
    }

    public Long getPrimaryBranchId() {
        return primaryBranchId;
    }

    public void setPrimaryBranchId(Long primaryBranchId) {
        this.primaryBranchId = primaryBranchId;
    }

    public List<String> getBranchNames() {
        return branchNames;
    }

    public void setBranchNames(List<String> branchNames) {
        this.branchNames = branchNames != null ? branchNames : new ArrayList<>();
    }

    public List<Branch> getActiveBranches() {
        return activeBranches;
    }

    public void setActiveBranches(List<Branch> activeBranches) {
        this.activeBranches = activeBranches != null ? activeBranches : new ArrayList<>();
    }

    public List<StaffSchedule> getUpcomingSchedules() {
        return upcomingSchedules;
    }

    public void setUpcomingSchedules(List<StaffSchedule> upcomingSchedules) {
        this.upcomingSchedules = upcomingSchedules != null ? upcomingSchedules : new ArrayList<>();
    }
}
