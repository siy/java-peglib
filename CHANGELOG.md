# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.5] - 2025-12-22

### Fixed

- **Cut Operator Scope**
  - Fixed CutFailure propagation beyond immediate Choice
  - Cut now correctly affects only the containing Choice, not parent Choices
  - Enables proper backtracking at higher grammar levels after cut failure

- **Error Position Tracking in Generated Parsers (ADVANCED mode)**
  - Fixed `trackFailure()` not being called in generated match methods
  - Error positions now correctly report the furthest position reached before failure
  - Previously, `furthestFailure` was always null causing fallback to current position after backtracking

## [0.1.4] - 2025-12-21

### Added

- **Cut Operator (`^` / `↑`)**
  - Commits to current choice alternative, prevents backtracking
  - Compatible with cpp-peglib syntax (both `^` and `↑` supported)
  - Provides accurate error positions after commitment
  - Works in both runtime and generated parsers
  - Example: `Rule <- ('if' ^ Statement) / ('while' ^ Statement)`

## [0.1.3] - 2025-12-21

### Fixed

- **Error Position Tracking**
  - Fixed error positions reporting 1:1 after PEG backtracking
  - Now tracks furthest position reached before failure for accurate error locations
  - Custom error messages preserved while using correct position

- **Java 25 Grammar** (synced from jbct-cli)
  - Added annotation support on enum constants (`@Deprecated RED`)
  - Fixed operator ambiguity with negative lookahead (`|` vs `||`, `&` vs `&&`, `-` vs `->`)
  - Fixed `QualifiedName` to not consume `.` before keywords like `class` (`String.class`)
  - Added keyword boundary helper rules to prevent whitespace issues in statements
  - Fixed `Member` rule order for better parsing of nested types

## [0.1.2] - 2025-12-20

### Fixed

- **Java 25 Grammar**
  - Contextual keywords (var, yield, record, sealed, non-sealed, permits, when, module) now allowed as identifiers
  - Generic method calls supported in PostOp rule (e.g., `foo.<Type>bar()`)
  - Added documentation for hard vs contextual keywords

- **Generated Parser (ADVANCED mode)**
  - Added missing `expected` field to `CstNode.Error` record
  - Fixed `attachTrailingTrivia` to preserve `expected` when reconstructing Error nodes

## [0.1.1] - 2025-12-20

### Fixed

- **Trivia Preservation**
  - Fixed trivia loss when Choice fails in sequence
  - Fixed trivia loss when Optional/ZeroOrMore fails in sequence
  - Extended `isReference` to handle wrapper expressions (Optional, ZeroOrMore, OneOrMore)
  - Fixed whitespace handling around predicates and references
  - Removed redundant `skipWhitespace()` calls that discard trivia

- **Java 25 Grammar**
  - Added `LocalTypeDecl` rule to support annotated and modified local type declarations
  - Local records/classes with `@Deprecated`, `final`, etc. now parse correctly

### Changed

- Test count: 242 → 243

## [0.1.0] - 2025-12-19

### Added

- **Core PEG Parsing Engine**
  - Full PEG grammar support compatible with cpp-peglib syntax
  - Packrat memoization for O(n) parsing complexity
  - Both CST (lossless) and AST (optimized) tree output

- **Grammar Features**
  - Sequences, ordered choice, quantifiers (`*`, `+`, `?`, `{n,m}`)
  - Lookahead predicates (`&e`, `!e`)
  - Character classes with negation and case-insensitivity
  - Token boundaries (`< e >`) for text capture
  - Named captures and back-references (`$name<e>`, `$name`)
  - Whitespace directive (`%whitespace`)

- **Inline Actions**
  - Java code blocks in grammar rules (`{ return sv.toInt(); }`)
  - SemanticValues API (`$0`, `$1`, `sv.token()`, `sv.get()`)
  - Runtime compilation via JDK Compiler API

- **Trivia Handling**
  - Whitespace and comments preserved as Trivia nodes
  - Leading and trailing trivia on all CST nodes
  - Line comments (`//`) and block comments (`/* */`) classification

- **Error Recovery**
  - Three strategies: NONE, BASIC, ADVANCED
  - Rust-style diagnostic formatting with source context
  - Multi-error collection with Error nodes in CST
  - Recovery at synchronization points (`,`, `;`, `}`, `)`, `]`, newline)

- **Source Code Generation**
  - Generate standalone parser Java files
  - Self-contained single file output
  - Only depends on pragmatica-lite:core
  - Type-safe RuleId sealed interface hierarchy
  - Optional ErrorReporting mode (BASIC/ADVANCED)
  - ADVANCED mode includes Rust-style diagnostics in generated parser

- **Test Suite**
  - 242 tests covering all features
  - Examples: Calculator, JSON, S-Expression, CSV, Java 25 grammar

### Dependencies

- Java 25
- pragmatica-lite:core 0.8.4
