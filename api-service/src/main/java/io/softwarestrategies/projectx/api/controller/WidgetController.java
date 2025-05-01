package io.softwarestrategies.projectx.api.controller;

import io.softwarestrategies.projectx.api.data.Widget;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequestMapping("/api/v1/widgets")
public class WidgetController {

    @GetMapping("/{id}")
    public ResponseEntity<?> getWidget(@PathVariable Long id, @RequestHeader String correlationId,
            HttpServletRequest headerServletRequest
    ) {
        log.info("Received request for widget with id: {} and correlationId: {}", id, correlationId);

        Widget widget = new Widget();
        widget.setId(id);
        widget.setName("Widget " + id);
        widget.setDescription("This is a widget with id: " + id);

        //return ResponseEntity.ok(widget.toString());
        return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @PostMapping
    public ResponseEntity<?> postWidget(@RequestBody(required = false) Widget widget) {
//        return ResponseEntity.ok("Data received at external POST endpoint");
        return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
