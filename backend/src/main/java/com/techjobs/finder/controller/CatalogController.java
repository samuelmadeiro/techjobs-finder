package com.techjobs.finder.controller;

import com.techjobs.finder.dto.ApiResponse;
import com.techjobs.finder.dto.CatalogDtos;
import com.techjobs.finder.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Catálogos usados para montar os filtros da interface. */
@RestController
@RequestMapping("/api")
@Tag(name = "Catálogos")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(summary = "Linguagens de programação disponíveis")
    @GetMapping("/languages")
    public ResponseEntity<ApiResponse<List<CatalogDtos.TechnologyResponse>>> languages() {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.languages()));
    }

    @Operation(summary = "Tecnologias, frameworks, bancos e ferramentas")
    @GetMapping("/technologies")
    public ResponseEntity<ApiResponse<List<CatalogDtos.TechnologyResponse>>> technologies() {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.technologies()));
    }

    @Operation(summary = "Países aceitos no filtro de busca",
            description = "O código é o valor de country em GET /api/jobs; nome e bandeira "
                    + "existem para a interface não manter a própria lista.")
    @GetMapping("/countries")
    public ResponseEntity<ApiResponse<List<CatalogDtos.CountryResponse>>> countries() {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.countries()));
    }

    @Operation(summary = "Empresas com vagas ativas")
    @GetMapping("/companies")
    public ResponseEntity<ApiResponse<List<CatalogDtos.CompanyResponse>>> companies() {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.companies()));
    }

    @Operation(summary = "Fontes cadastradas e status da última coleta")
    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<List<CatalogDtos.SourceResponse>>> sources() {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.sources()));
    }
}
