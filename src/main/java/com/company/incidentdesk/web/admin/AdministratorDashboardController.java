package com.company.incidentdesk.web.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.company.incidentdesk.web.admin.AdministratorDashboardQuery.Assignment;
import com.company.incidentdesk.web.admin.AdministratorDashboardQuery.DashboardCriteria;
import com.company.incidentdesk.web.admin.AdministratorDashboardQuery.DashboardPage;
import com.company.incidentdesk.web.admin.AdministratorDashboardQuery.Direction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for the protected, server-paginated administrator dashboard. */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@ConditionalOnBean(DataSource.class)
public class AdministratorDashboardController {
    private final AdministratorDashboardQuery dashboard;

    public AdministratorDashboardController(AdministratorDashboardQuery dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    ResponseEntity<DashboardPage> dashboard(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") List<String> category,
            @RequestParam(defaultValue = "") List<String> status,
            @RequestParam(defaultValue = "ALL") Assignment assignment,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdThrough,
            @RequestParam(required = false) UUID reporterId,
            @RequestParam(required = false) UUID responderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") Direction direction) {
        DashboardPage result = dashboard.find(new DashboardCriteria(
                search, removeEmpty(category), removeEmpty(status), assignment,
                createdFrom, createdThrough, reporterId, responderId, page, size, sort, direction));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private static List<String> removeEmpty(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }
}
