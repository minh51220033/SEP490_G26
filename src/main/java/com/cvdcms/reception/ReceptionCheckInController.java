package com.cvdcms.reception;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;
@Controller
@RequestMapping("/reception/check-in")
public class ReceptionCheckInController {
    private final ReceptionService service;
    public ReceptionCheckInController(ReceptionService service){this.service=service;}
    @GetMapping public String page(@RequestParam(required=false) Long branchId,@RequestParam(required=false) String query,HttpSession session,Model model){
        model.addAttribute("dashboard",service.dashboard(session,branchId,query));model.addAttribute("roleNames",List.of("LE_TAN"));model.addAttribute("sidebarMenu","checkin");return "reception/check-in";}
    @PostMapping("/appointments/{id}") public String appointment(@PathVariable Long id,@RequestParam Long branchId,@RequestParam(required=false) String symptoms,@RequestParam String requestKey,HttpSession session,RedirectAttributes redirect){
        try{var r=service.checkInAppointment(session,id,symptoms,requestKey);redirect.addFlashAttribute("notice","Đã check-in "+r.patientName()+" – lượt khám "+r.code()+". Chuyển sang F08 để phân công.");}catch(ResponseStatusException e){redirect.addFlashAttribute("error",e.getReason());}
        return "redirect:/reception/check-in?branchId="+branchId;}
    @PostMapping("/walk-ins") public String walkIn(@RequestParam Long branchId,@RequestParam Long profileId,@RequestParam String symptoms,@RequestParam(defaultValue="false") boolean emergency,@RequestParam String requestKey,HttpSession session,RedirectAttributes redirect){
        try{var r=service.checkInWalkIn(session,branchId,profileId,symptoms,emergency,requestKey);redirect.addFlashAttribute("notice","Đã check-in "+r.patientName()+" – lượt khám "+r.code()+". Chuyển sang F08 để phân công.");}catch(ResponseStatusException e){redirect.addFlashAttribute("error",e.getReason());}
        return "redirect:/reception/check-in?branchId="+branchId;}
}
