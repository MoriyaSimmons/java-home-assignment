package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.service.LeaveRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leave-requests")
public class LeaveRequestsController {

    private final LeaveRequestService leaveRequestService;

    public LeaveRequestsController(LeaveRequestService leaveRequestService) {
        this.leaveRequestService = leaveRequestService;
    }

    // GET /api/leave-requests
    @GetMapping
    public ResponseEntity<List<LeaveRequest>> getAll() {
        return ResponseEntity.ok(leaveRequestService.getAll());
    }

    // GET /api/leave-requests/search?name=Dana
    // Lets the UI quickly find requests by employee name.
    @GetMapping("/search")
    public ResponseEntity<List<LeaveRequest>> search(@RequestParam String name) {
        return ResponseEntity.ok(leaveRequestService.search(name));
    }

    // POST /api/leave-requests
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateLeaveRequestDto dto) {
        return leaveRequestService.create(dto);
    }
}
