import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Employee } from '../models/leave-request.model';

function startNotAfterEnd(group: AbstractControl): ValidationErrors | null {
  const start = group.get('startDate')?.value;
  const end = group.get('endDate')?.value;
  if (!start || !end) {
    return null;
  }
  return start > end ? { startAfterEnd: true } : null;
}

// NOTE: This component was written quickly for a POC.
// It talks to the API directly, manages state by hand and uses `any` everywhere.
@Component({
  selector: 'app-leave-requests',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './leave-requests.component.html',
  styleUrls: ['./leave-requests.component.css']
})
export class LeaveRequestsComponent implements OnInit {
  requests: any[] = [];
  employees: Employee[] = [];
  loading = false;
  submitting = false;
  createError = '';
  approvingIds = new Set<number>();
  approveSuccess = '';
  approveError = '';

  form = this.fb.group({
    employeeId: [null as number | null, Validators.required],
    type: [null as number | null, Validators.required],
    startDate: ['', Validators.required],
    endDate: ['', Validators.required]
  }, { validators: startNotAfterEnd });

  private apiUrl = 'http://localhost:5080/api/leave-requests';
  private employeesUrl = 'http://localhost:5080/api/employees';

  constructor(private http: HttpClient, private fb: FormBuilder) {}

  ngOnInit(): void {
    this.load();
    this.http.get<Employee[]>(this.employeesUrl).subscribe((data) => {
      this.employees = data;
    });
  }

  get days(): number | null {
    const start = this.form.get('startDate')?.value;
    const end = this.form.get('endDate')?.value;
    if (!start || !end) {
      return null;
    }
    return this.inclusiveDays(start, end);
  }

  load(): void {
    this.loading = true;
    this.http.get<any>(this.apiUrl).subscribe((data) => {
      this.requests = data;
      this.loading = false;
    });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting = true;
    this.createError = '';

    const employeeId = Number(this.form.value.employeeId);
    const type = Number(this.form.value.type);
    const startDate = this.form.value.startDate;
    const endDate = this.form.value.endDate;

    this.http.post<any>(this.apiUrl, { employeeId, type, startDate, endDate }).subscribe({
      next: (created) => {
        const employee = this.employees.find((e) => e.id === employeeId);
        this.requests = [{ ...created, employee }, ...this.requests];
        this.form.reset({ employeeId: null, type: null, startDate: '', endDate: '' });
        this.submitting = false;
      },
      error: (err) => {
        this.createError = typeof err.error === 'string' && err.error
          ? err.error
          : 'Could not create the leave request.';
        this.submitting = false;
      }
    });
  }

  isApproving(id: number): boolean {
    return this.approvingIds.has(id);
  }

  approve(id: number): void {
    if (this.approvingIds.has(id)) {
      return;
    }

    this.approvingIds = new Set(this.approvingIds).add(id);
    this.approveSuccess = '';
    this.approveError = '';

    this.http.post<any>(this.apiUrl + '/' + id + '/approve', {}).subscribe({
      next: (updated) => {
        this.requests = this.requests.map((r) => {
          if (r.id !== id) {
            return r;
          }
          return { ...r, ...updated, employee: updated.employee ?? r.employee };
        });
        this.approveSuccess = 'Leave request #' + id + ' was approved.';
        this.clearApproving(id);
      },
      error: (err) => {
        this.approveError = this.approveErrorMessage(err);
        this.clearApproving(id);
      }
    });
  }

  typeLabel(type: number): string {
    if (type == 0) return 'Vacation';
    if (type == 1) return 'Sick';
    return 'Unpaid';
  }

  statusLabel(status: number): string {
    if (status == 0) return 'Pending';
    if (status == 1) return 'Approved';
    return 'Rejected';
  }

  private clearApproving(id: number): void {
    const next = new Set(this.approvingIds);
    next.delete(id);
    this.approvingIds = next;
  }

  private approveErrorMessage(err: any): string {
    if (typeof err.error === 'string' && err.error) {
      return err.error;
    }
    if (err.status === 409) {
      return 'This leave request cannot be approved.';
    }
    if (err.status === 404) {
      return 'Leave request not found.';
    }
    return 'Could not approve the leave request.';
  }

  private inclusiveDays(start: string, end: string): number {
    const startParts = start.split('-').map(Number);
    const endParts = end.split('-').map(Number);
    const startDate = new Date(startParts[0], startParts[1] - 1, startParts[2]);
    const endDate = new Date(endParts[0], endParts[1] - 1, endParts[2]);
    const msPerDay = 24 * 60 * 60 * 1000;
    return Math.round((endDate.getTime() - startDate.getTime()) / msPerDay) + 1;
  }
}
