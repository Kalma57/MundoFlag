package com.example.flagdemo.View;

import com.example.flagdemo.DataAccessLayer.CountryNameAliases;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Serves the alternative country names (Ivory Coast, Holland, USA...) to the shared country
 * autocomplete in /js/mobile.js, so typing "ivory" can suggest "Côte d’Ivoire". The server
 * already accepts these names on its own (see CountryNameAliases) - this is only for the
 * suggestion list.
 */
@RestController
public class CountryNamesController {

    @GetMapping("/countries/aliases")
    public ResponseEntity<Map<String, List<String>>> aliases() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(CountryNameAliases.ALIASES);
    }
}
