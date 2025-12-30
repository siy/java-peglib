# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.7] - 2025-12-30

### Changed

- **JBCT Compliance Refactoring**
  - Replaced null usage with `Option<T>` throughout the codebase
  - `ParseResultWithDiagnostics.node()` now returns `Option<CstNode>`
  - `ParseMode` uses `Option` for nullable fields
  - `ParsingContext` packrat cache uses `Option`
  - `Diagnostic.code()` now returns `Option<String>`
  - Generated `ParseResult` and `CstParseResult` use `Option` for nullable fields
  - Improved type safety with `SemanticValues`

### Added

- **JBCT Maven Plugin** - Added jbct-maven-plugin 0.4.1 for code formatting and linting
- **Internal Type Tests** - 24 new tests for ParseResult, ParsingContext, and generated parser diagnostics

- Test count: 271 → 305

## [0.1.6] - 2025-12-26

### Added

- **AST Support in Generated Parsers**
  - Generated CST parsers now include `AstNode` type and `parseAst()` method
  - Allows parsing directly to AST (without trivia) from generated parsers

- **Packrat Toggle in Generated Parsers**
  - Added `setPackratEnabled(boolean)` method to generated parsers
  - Allows disabling memoization at runtime to reduce memory usage for large inputs

- **Unlimited Action Variable Support**
  - Action code now supports unlimited `$N` positional variables (previously limited to `$1-$20`)
  - Uses regex-based substitution for flexibility

### Fixed

- **Grammar Validation**
  - Implemented `Grammar.validate()` to detect undefined rule references
  - Recursively walks all expressions and reports first undefined reference with location
  - Previously, grammars with typos in rule names would fail at parse time with cryptic errors

- **Thread Safety in Whitespace Skipping**
  - Moved `skippingWhitespace` flag from `PegEngine` (per-instance) to `ParsingContext` (per-parse)
  - Fixes potential race conditions when reusing parser instances across threads

- **Packrat Cache Key Collision Risk**
  - Changed cache key from `hashCode()` to unique sequential IDs
  - Eliminates theoretical collision bugs with different rule names having same hash

### Changed

- **Builder API Naming Standardized**
  - `PegParser.Builder` methods renamed for consistency: `withPackrat()` → `packrat()`, `withTrivia()` → `trivia()`, `withErrorRecovery()` → `recovery()`
  - Removed duplicate `ParserConfig.Builder` (unused)

- **Documentation Cleanup**
  - Removed undocumented `%word` directive from documentation (feature not implemented)
  - Removed unused placeholder `skipWhitespace()` method from `ParsingContext`

- **Code Simplification**
  - Consolidated 3 duplicate expression parsing switch statements into unified `parseExpressionWithMode()`
  - Extracted `buildParseError()` helper to eliminate duplicate error message construction
  - Removed unused `SemanticValues.choice` field and getter
  - Removed unused `SourceLocation.advanceColumn()`/`advanceLine()` methods
  - ~120 lines of duplicate code eliminated

- Test count: 268 → 271
- Updated pragmatica-lite dependency: 0.8.4 → 0.9.0

## [0.1.5] - 2025-12-22

### Fixed

- **CutFailure Propagation Through Repetitions**
  - Fixed CutFailure not propagating through repetitions (ZeroOrMore, OneOrMore, Optional, Repetition)
  - Previously, repetitions would treat CutFailure as "end of repetition" and succeed with partial results
  - Now CutFailure correctly propagates up, preventing silent backtracking after commit
  - Fixes issue where parse errors were reported at wrong positions (e.g., start of class instead of actual error)

- **CutFailure Propagation Through Choices**
  - CutFailure now propagates through Choice rules instead of being converted to regular Failure
  - Enables cuts in nested rules to affect parent rule behavior correctly

- **Word Boundary Checks in Grammars with Cuts**
  - Added word boundary checks (`![a-zA-Z0-9_$]`) before cuts in type declarations
  - Prevents false commits when keyword is prefix of identifier (e.g., `record` in `recordResult`)

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
