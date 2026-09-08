package com.example.leavemanagement;

import com.example.leavemanagement.controller.LeaveRequestsController;
import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Runs against a real, throwaway PostgreSQL started by Testcontainers.
// (Docker must be available on the machine running the tests.)
@SpringBootTest
@Testcontainers
class LeaveRequestsTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LeaveRequestsController controller;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository leaveRequests;

    @Test
    void create_WithinQuota_Succeeds() {
        // Arrange
        Employee emp = new Employee();
        emp.setName("Test Emp");
        emp.setAnnualQuota(20);
        employees.save(emp);

        long before = leaveRequests.count();

        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 3)); // 3 days, well within the quota

        // Act
        ResponseEntity<?> result = controller.create(dto);

        // Assert
        assertTrue(result.getStatusCode().is2xxSuccessful());
        assertEquals(before + 1, leaveRequests.count());
    }

    @Test
    void create_ExceedsQuotaGivenExistingApprovedVacation_IsRejectedAndNotPersisted() {
        Employee emp = new Employee();
        emp.setName("Quota Emp");
        emp.setAnnualQuota(20);
        employees.save(emp);

        LeaveRequest alreadyApproved = new LeaveRequest();
        alreadyApproved.setEmployeeId(emp.getId());
        alreadyApproved.setType(LeaveType.VACATION);
        alreadyApproved.setStartDate(LocalDate.of(2026, 1, 6));
        alreadyApproved.setEndDate(LocalDate.of(2026, 1, 23));
        alreadyApproved.setDays(18);
        alreadyApproved.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(alreadyApproved);

        long before = leaveRequests.count();

        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 3)); // 3 days; 18 + 3 exceeds quota of 20

        ResponseEntity<?> result = controller.create(dto);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals("Not enough vacation balance", result.getBody());
        assertEquals(before, leaveRequests.count());
    }

    @Test
    void approve_UnknownId_Returns404() {
        ResponseEntity<?> result = controller.approve(999_999L);

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
        assertEquals("Leave request not found", result.getBody());
    }

    @Test
    void approve_AlreadyApproved_Returns409() {
        Employee emp = employees.save(employee("Already Approved Emp", 20));
        LeaveRequest request = leaveRequests.save(vacation(emp.getId(), LeaveStatus.APPROVED, 3,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3)));

        ResponseEntity<?> result = controller.approve(request.getId());

        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("Leave request is already approved", result.getBody());
        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_Rejected_Returns409() {
        Employee emp = employees.save(employee("Rejected Emp", 20));
        LeaveRequest request = leaveRequests.save(vacation(emp.getId(), LeaveStatus.REJECTED, 3,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3)));

        ResponseEntity<?> result = controller.approve(request.getId());

        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("Leave request is already rejected", result.getBody());
        assertEquals(LeaveStatus.REJECTED, leaveRequests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_PendingVacationWithinRemainingQuota_Succeeds() {
        Employee emp = employees.save(employee("Remaining Quota Emp", 20));
        leaveRequests.save(vacation(emp.getId(), LeaveStatus.APPROVED, 18,
                LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 23)));
        LeaveRequest pending = leaveRequests.save(vacation(emp.getId(), LeaveStatus.PENDING, 2,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2)));

        ResponseEntity<?> result = controller.approve(pending.getId());

        assertEquals(HttpStatus.OK, result.getStatusCode());
        LeaveRequest body = (LeaveRequest) result.getBody();
        assertNotNull(body);
        assertEquals(LeaveStatus.APPROVED, body.getStatus());
        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_PendingVacationExceedingRemainingQuota_Returns409AndRemainsPending() {
        Employee emp = employees.save(employee("Over Quota Emp", 20));
        leaveRequests.save(vacation(emp.getId(), LeaveStatus.APPROVED, 18,
                LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 23)));
        LeaveRequest pending = leaveRequests.save(vacation(emp.getId(), LeaveStatus.PENDING, 3,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3)));

        ResponseEntity<?> result = controller.approve(pending.getId());

        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("Not enough vacation balance", result.getBody());
        assertEquals(LeaveStatus.PENDING, leaveRequests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_TwoPendingVacationsTogetherExceedQuota_SecondReturns409() {
        Employee emp = employees.save(employee("Concurrent Quota Emp", 20));
        LeaveRequest first = leaveRequests.save(vacation(emp.getId(), LeaveStatus.PENDING, 15,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 15)));
        LeaveRequest second = leaveRequests.save(vacation(emp.getId(), LeaveStatus.PENDING, 15,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15)));

        ResponseEntity<?> firstResult = controller.approve(first.getId());
        ResponseEntity<?> secondResult = controller.approve(second.getId());

        assertEquals(HttpStatus.OK, firstResult.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, secondResult.getStatusCode());
        assertEquals("Not enough vacation balance", secondResult.getBody());
        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(first.getId()).orElseThrow().getStatus());
        assertEquals(LeaveStatus.PENDING, leaveRequests.findById(second.getId()).orElseThrow().getStatus());
    }

    @Test
    void search_InputWithApostrophe_IsBoundAndDoesNotInjectSql() {
        Employee obrien = employees.save(employee("O'Brien", 20));
        Employee other = employees.save(employee("Other Emp", 20));
        LeaveRequest obrienRequest = leaveRequests.save(vacation(obrien.getId(), LeaveStatus.PENDING, 2,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2)));
        LeaveRequest otherRequest = leaveRequests.save(vacation(other.getId(), LeaveStatus.PENDING, 2,
                LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 4)));

        ResponseEntity<List<LeaveRequest>> byName = controller.search("O'Brien");
        assertEquals(HttpStatus.OK, byName.getStatusCode());
        List<LeaveRequest> named = byName.getBody();
        assertNotNull(named);
        assertEquals(1, named.size());
        assertEquals(obrienRequest.getId(), named.get(0).getId());

        ResponseEntity<List<LeaveRequest>> injectionAttempt = controller.search("' OR '1'='1");
        assertEquals(HttpStatus.OK, injectionAttempt.getStatusCode());
        List<LeaveRequest> injected = injectionAttempt.getBody();
        assertNotNull(injected);
        assertTrue(injected.stream().noneMatch(r -> r.getId().equals(otherRequest.getId())));
        assertTrue(injected.stream().noneMatch(r -> r.getId().equals(obrienRequest.getId())));
    }

    private static Employee employee(String name, int annualQuota) {
        Employee emp = new Employee();
        emp.setName(name);
        emp.setAnnualQuota(annualQuota);
        return emp;
    }

    private static LeaveRequest vacation(Long employeeId, LeaveStatus status, int days,
                                         LocalDate startDate, LocalDate endDate) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(employeeId);
        request.setType(LeaveType.VACATION);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setDays(days);
        request.setStatus(status);
        return request;
    }
}
