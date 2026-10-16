package com.cvdcms.DTO;


import java.util.ArrayList;
import java.util.List;

public class CompleteProfilesDTO {

    private List<PatientProfileFormDTO> profiles = new ArrayList<>();

    public List<PatientProfileFormDTO> getProfiles() {
        return profiles;
    }

    public void setProfiles(List<PatientProfileFormDTO> profiles) {
        this.profiles = profiles;
    }
}

