package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class LeaveRequestService {

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public LeaveRequestService(EmployeeRepository employeeRepository,
                               LeaveRequestRepository leaveRequestRepository) {
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    public List<LeaveRequest> getAll() {
        return leaveRequestRepository.findAll().stream()
                .sorted((a, b) -> b.getStartDate().compareTo(a.getStartDate()))
                .toList();
    }

    public List<LeaveRequest> search(String name) {
        String sql = "SELECT * FROM leave_requests WHERE employee_id IN " +
                "(SELECT id FROM employees WHERE name LIKE '%" + name + "%')";

        @SuppressWarnings("unchecked")
        List<LeaveRequest> results = entityManager
                .createNativeQuery(sql, LeaveRequest.class)
                .getResultList();

        return results;
    }

    @Transactional
    public ResponseEntity<?> create(CreateLeaveRequestDto dto) {
        if (dto.getEmployeeId() == null) {
            return ResponseEntity.badRequest().body("Employee id is required");
        }
        if (dto.getType() == null) {
            return ResponseEntity.badRequest().body("Leave type is required");
        }
        if (dto.getStartDate() == null) {
            return ResponseEntity.badRequest().body("Start date is required");
        }
        if (dto.getEndDate() == null) {
            return ResponseEntity.badRequest().body("End date is required");
        }
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            return ResponseEntity.badRequest().body("Start date must not be after end date");
        }

        Employee employee = employeeRepository.findById(dto.getEmployeeId()).orElse(null);
        if (employee == null) {
            return ResponseEntity.status(404).body("Employee not found");
        }

        int days = (int) ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate()) + 1;

        int used = leaveRequestRepository
                .findByEmployeeIdAndTypeAndStatus(dto.getEmployeeId(), LeaveType.VACATION, LeaveStatus.APPROVED)
                .stream()
                .mapToInt(LeaveRequest::getDays)
                .sum();

        if (dto.getType() == LeaveType.VACATION && used + days > employee.getAnnualQuota()) {
            return ResponseEntity.badRequest().body("Not enough vacation balance");
        }

        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(dto.getEmployeeId());
        request.setType(dto.getType());
        request.setStartDate(dto.getStartDate());
        request.setEndDate(dto.getEndDate());
        request.setDays(days);
        request.setStatus(LeaveStatus.PENDING);

        leaveRequestRepository.save(request);

        return ResponseEntity.ok(request);
    }

    @Transactional
    public ResponseEntity<?> approve(Long id) {
        LeaveRequest request = leaveRequestRepository.findById(id).orElse(null);
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Leave request not found");
        }
        if (request.getStatus() == LeaveStatus.APPROVED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Leave request is already approved");
        }
        if (request.getStatus() == LeaveStatus.REJECTED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Leave request is already rejected");
        }

        Employee employee = employeeRepository.findByIdForUpdate(request.getEmployeeId()).orElse(null);
        if (employee == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Employee not found");
        }

        entityManager.refresh(request);
        if (request.getStatus() == LeaveStatus.APPROVED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Leave request is already approved");
        }
        if (request.getStatus() == LeaveStatus.REJECTED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Leave request is already rejected");
        }
        if (request.getStatus() != LeaveStatus.PENDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Leave request cannot be approved");
        }

        if (request.getType() == LeaveType.VACATION) {
            int used = leaveRequestRepository
                    .findByEmployeeIdAndTypeAndStatus(request.getEmployeeId(), LeaveType.VACATION, LeaveStatus.APPROVED)
                    .stream()
                    .mapToInt(LeaveRequest::getDays)
                    .sum();
            if (used + request.getDays() > employee.getAnnualQuota()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body("Not enough vacation balance");
            }
        }

        request.setStatus(LeaveStatus.APPROVED);
        leaveRequestRepository.save(request);
        return ResponseEntity.ok(request);
    }
}
