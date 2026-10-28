package com.cvdcms.reception;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/reception/check-ins")
public class ReceptionCheckInApi {
    private final ReceptionService service; public ReceptionCheckInApi(ReceptionService service){this.service=service;}
    @PostMapping("/appointments/{id}") public ReceptionService.VisitResult appointment(@PathVariable Long id,@Valid @RequestBody AppointmentRequest request,HttpSession session){return service.checkInAppointment(session,id,request.symptoms(),request.requestKey());}
    @PostMapping("/walk-ins") @ResponseStatus(HttpStatus.CREATED) public ReceptionService.VisitResult walkIn(@Valid @RequestBody WalkInRequest request,HttpSession session){return service.checkInWalkIn(session,request.branchId(),request.profileId(),request.symptoms(),request.emergency(),request.requestKey());}
    public record AppointmentRequest(String symptoms,@NotBlank String requestKey){}
    public record WalkInRequest(@NotNull Long branchId,@NotNull Long profileId,@NotBlank String symptoms,boolean emergency,@NotBlank String requestKey){}
}
