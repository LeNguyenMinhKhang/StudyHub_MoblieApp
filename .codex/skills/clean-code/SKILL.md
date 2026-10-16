---
name: clean-code
description: Pragmatic coding standards - concise, direct, no over-engineering, no unnecessary comments
allowed-tools: Read, Write, Edit
version: 2.0
priority: CRITICAL
---

# Clean Code - Pragmatic AI Coding Standards

> **CRITICAL SKILL** - Be **concise, direct, and solution-focused**.

---

## Core Principles

| Principle | Rule |
|-----------|------|
| **SRP** | Single Responsibility - each function/class does ONE thing |
| **DRY** | Don't Repeat Yourself - extract duplicates, reuse |
| **KISS** | Keep It Simple - simplest solution that works |
| **YAGNI** | You Aren't Gonna Need It - don't build unused features |
| **Boy Scout** | Leave code cleaner than you found it |

---

## Naming Rules

| Element | Convention |
|---------|------------|
| **Variables** | Reveal intent: `userCount` not `n` |
| **Functions** | Verb + noun: `getUserById()` not `user()` |
| **Booleans** | Question form: `isActive`, `hasPermission`, `canEdit` |
| **Constants** | SCREAMING_SNAKE: `MAX_RETRY_COUNT` |

> **Rule:** If you need a comment to explain a name, rename it.

---

## Use Intention-Revealing Names

> **Tip:** Code is read way more often than it is written. A name must instantly answer why it exists and what it does.

### The Three Questions

Every name should answer:
1. **Why does it exist?**
2. **What does it do?**
3. **How is it used?**

### ❌ Bad: Requires Comment to Understand

```typescript
let d; // elapsed time in days

let n = 5; // max login attempts

let arr = []; // active user accounts
```

**Problem:** If you need to scroll back to the definition, the name has failed.

### ✅ Good: Self-Documenting Names

```typescript
let elapsedTimeInDays;

const maxLoginAttempts = 5;

const activeUserAccounts = [];

let unreadMessageCount;
```

**Benefit:** The name IS the comment. No explanation needed.

### Quick Check

| ❌ Needs Comment | ✅ Self-Explaining |
|-----------------|---------------------|
| `let d; // days` | `let elapsedDays` |
| `let t; // temp` | `let temporaryUserData` |
| `let n; // count` | `let itemCount` |
| `let x; // position` | `let horizontalPosition` |
| `let flag; // is active` | `let isActive` |

### The Test

Read your variable name out loud. Does it make sense without context?

- ❌ "What is `d`?" → Need to check definition
- ✅ "What is `elapsedTimeInDays`?" → Obvious!

> **Rule:** If you have to scroll back to understand a name, rename it.

---

## Use Pronounceable Names

> **Tip:** Your code is read by humans, so make it sound like human language.

### Why It Matters

Programming is a **social activity**. You discuss code in:
- Stand-up meetings
- Code reviews
- Pair programming
- Slack/Teams messages

If you can't pronounce it, you can't talk about it efficiently.

### ❌ Bad: Alphabet Soup

```typescript
let genymdhms = new Date();  // "gen-why-em-dee-aych-em-ess"???
let prcssr = new Processor();
let usrRcrds = [];
let dtaRcrd = fetchData();
```

**Problem:** "Hey, can you check the... gen-yim-duh-hims variable?" 🤔

### ✅ Good: Human Language

```typescript
let generationTimestamp = new Date();
let processor = new Processor();
let userRecords = [];
let dataRecord = fetchData();
```

**Benefit:** "Hey, can you check the generation timestamp?" ✅

### Quick Reference

| ❌ Unpronounceable | ✅ Pronounceable |
|-------------------|------------------|
| `genymdhms` | `generationTimestamp` |
| `prcssr` | `processor` |
| `usrRcrds` | `userRecords` |
| `dtaRcrd` | `dataRecord` |
| `cstmrMgr` | `customerManager` |
| `txCalc` | `taxCalculator` |

> **Rule:** If it doesn't sound like English, it's probably a poor name.

---

## Make Meaningful Distinctions

> **Tip:** If you have to check the implementation to understand the name, the name failed.

### The Problem: Noise Words

These suffixes/variations add no information:
- `User` vs `UserInfo` vs `UserData` vs `UserObject`
- `getAccount()` vs `getAccountInfo()` vs `getAccountData()`
- `Product` vs `ProductDetails` vs `ProductInfo`

### ❌ Bad: Non-Informative Names

```typescript
function filterAndMap(data1, data2, data3) {
  const result = data1.filter(data2).map(data3);
  return result;
}

// Caller has no idea what to pass
filterAndMap(???, ???, ???);
```

**Problem:** What's the difference between `data1`, `data2`, `data3`?

### ✅ Good: Names Reveal Intent

```typescript
function transformUsers(users, filterFn, mapFn) {
  const result = users.filter(filterFn).map(mapFn);
  return result;
}

// Clear what to pass
transformUsers(activeUsers, isPremium, formatDisplayName);
```

### Noise Words to Avoid

| ❌ Noise Word | ✅ Better Alternative |
|--------------|----------------------|
| `UserInfo` | `User` (just use one) |
| `ProductData` | `Product` |
| `AccountObject` | `Account` |
| `a1`, `a2`, `a3` | Descriptive names |
| `theUser` | `user` |
| `aProduct` | `product` |

### The Test

Ask yourself: **"Can I tell the difference without reading the implementation?"**
- ❌ `getUser()` vs `getUserInfo()` → No distinction
- ✅ `getUser()` vs `getUserPermissions()` → Clear distinction

> **Rule:** If two names differ only by noise words, one of them is wrong.

---

## Avoid Disinformation (Don't Lie with Names)

> **Tip:** Trust in code starts with honest naming. If a name promises something it doesn't deliver, you're creating disinformation.

### The Problem

Misleading names:
- Break developer trust
- Force extensive tracing
- Are a massive source of subtle bugs

### ❌ Bad: Names That Lie

```typescript
// Promises a Map, delivers a boolean
let settingsMap = true;

// Promises a List/Array, delivers an Object
let accountList = { id: 1, name: "John" };

// Promises a count, delivers a boolean
let userCount = false;
```

**Problem:** Developer expects `settingsMap.get("theme")` → crashes!

### ✅ Good: Honest Names

```typescript
// Name matches type
let hasSettings = true;
let account = { id: 1, name: "John" };
let hasUsers = false;

// Or if you really need a Map/List
let settingsMap = new Map([["theme", "dark"]]);
let accounts: Account[] = [account1, account2];
```

### Type-Implying Words to Use Carefully

| Word | Implies | Only Use For |
|------|---------|--------------|
| `List` | Array/ordered collection | `Account[]` |
| `Map` | Key-value pairs | `Map<K, V>` |
| `Set` | Unique values | `Set<T>` |
| `Table` | 2D data | Array of arrays |
| `Count` / `Total` | Number | `number` |
| `Flag` / `Is` / `Has` | Boolean | `boolean` |

### The Promise Test

A variable name is a **promise** of what it contains.

| Name | Promise | Must Deliver |
|------|---------|--------------|
| `userList` | Array of users | `User[]` |
| `isActive` | Yes/No answer | `boolean` |
| `orderCount` | A number | `number` |
| `configMap` | Key-value lookup | `Map` or `Record` |

> **Rule:** If a name promises something it doesn't deliver, it's disinformation. Rename it.

---

## Avoid Mental Mapping

> **Tip:** Every cryptic variable forces the reader to mentally map it to its meaning. Don't make them guess.

### ❌ Bad: Cryptic Names

```typescript
const r = 'https://api.example.com';
const u = fetchUser(r);
const p = u.permissions;
const a = p.includes('admin');

// Mathematical example
const hp = Math.sqrt(a*a + b*b);
```

**Problem:** Reader must remember: "r is url, u is user, p is permissions, a is... wait, is a the admin check or a side of the triangle?"

### ✅ Good: Meaningful Names

```typescript
const apiUrl = 'https://api.example.com';
const user = fetchUser(apiUrl);
const permissions = user.permissions;
const isAdmin = permissions.includes('admin');

// Mathematical example
const hypotenuse = Math.sqrt(sideA*sideA + sideB*sideB);
```

**Benefit:** Code reads like English. Zero mental effort required.

### Quick Reference

| ❌ Cryptic | ✅ Meaningful |
|-----------|---------------|
| `r`, `u`, `p` | `url`, `user`, `permissions` |
| `a`, `b`, `c` | `isAdmin`, `sideA`, `sideB` |
| `hp` | `hypotenuse` |
| `idx`, `cnt` | `index`, `count` |
| `tmp`, `temp` | Describe what it actually holds |
| `data`, `info` | Be specific: `userData`, `orderInfo` |

### Exception: Loop Counters

Single letters are OK for simple loop counters:
```typescript
for (let i = 0; i < items.length; i++) { ... }  // ✅ OK
```

> **Rule:** If the variable lives longer than 3 lines, give it a real name.

---

## Avoid Encodings (No Hungarian Notation)

> **Tip:** Let your tools handle the types while you focus on logic.

### ❌ Bad: Type Prefixes

```typescript
let strFirstName: string;
let iAge: number;
let bIsLoggedIn: boolean;
let fAccountBalance: number;
let arrUserList: User[];
```

**Problems:**
- Redundant: IDE already shows types on hover
- Maintenance nightmare: change `int` to `float`? Rename everywhere!
- Noise: prefixes distract from actual meaning

### ✅ Good: Describe Purpose, Not Type

```typescript
let firstName: string;
let age: number;
let isLoggedIn: boolean;
let accountBalance: number;
let users: User[];
```

**Benefit:** Name describes WHAT it represents, not what it IS.

### Quick Reference

| ❌ With Encoding | ✅ Without Encoding |
|-----------------|---------------------|
| `strName` | `name` |
| `iCount`, `intCount` | `count` |
| `bFlag`, `boolActive` | `isActive` |
| `fPrice`, `floatTotal` | `price`, `total` |
| `arrItems`, `lstUsers` | `items`, `users` |
| `objConfig` | `config` |

### Why This Matters

| Old World (C, VB6) | Modern World (TS, IDE) |
|--------------------|------------------------|
| No type hints | Hover shows type |
| No autocomplete | Full IntelliSense |
| Manual tracking | Compiler checks types |

> **Rule:** The type is already handled by your tools. Don't encode it in names.

---

## Use Searchable Names

> **Tip:** Searchable names make the code navigable.

### ❌ Bad: Magic Numbers

```typescript
for (let i = 0; i < 7; i++) {
  registerStudent(classes[i]);
}

// Later in another file...
if (student.classCount > 7) {
  throw new Error("Too many classes");
}
```

**Problem:** Search for "7" → hundreds of irrelevant results. What does 7 mean?

### ✅ Good: Named Constants

```typescript
const MAX_CLASSES_PER_STUDENT = 7;

for (let i = 0; i < MAX_CLASSES_PER_STUDENT; i++) {
  registerStudent(classes[i]);
}

// Later in another file...
if (student.classCount > MAX_CLASSES_PER_STUDENT) {
  throw new Error("Too many classes");
}
```

**Benefit:** Search for `MAX_CLASSES_PER_STUDENT` → 1 result. Find it instantly!

### Common Magic Numbers to Replace

| ❌ Magic Number | ✅ Named Constant |
|----------------|-------------------|
| `7` | `MAX_CLASSES_PER_STUDENT` |
| `86400` | `SECONDS_PER_DAY` |
| `1000` | `MILLISECONDS_PER_SECOND` |
| `200` | `HTTP_OK` |
| `0.1` | `TAX_RATE` or `DISCOUNT_RATE` |
| `5` | `MAX_RETRY_ATTEMPTS` |

### Name Length vs Scope

| Scope | Name Length | Example |
|-------|-------------|---------|
| Loop counter | Short OK | `i`, `j` |
| Local variable | Medium | `userName` |
| Class field | Descriptive | `customerEmailAddress` |
| Global constant | Very descriptive | `MAX_CLASSES_PER_STUDENT` |

> **Rule:** If you can't find it by searching, the name is too generic.

---

## Function Rules

| Rule | Description |
|------|-------------|
| **Small** | Max 20 lines, ideally 5-10 |
| **One Thing** | Does one thing, does it well |
| **One Level** | One level of abstraction per function |
| **Few Args** | Max 3 arguments, prefer 0-2 |
| **No Side Effects** | Don't mutate inputs unexpectedly |

---

## Bury the Switch (Hide Conditional Logic)

> **Tip:** Keep your high-level logic clean by burying switch statements.

### ❌ Bad: Switch in High-Level Function

```typescript
function calculatePay(employee: Employee): number {
  switch (employee.type) {
    case 'fullTime':
      return employee.salary / 12;
    case 'partTime':
      return employee.hours * employee.rate;
    case 'contractor':
      return employee.hours * employee.contractRate;
    default:
      throw new Error('Unknown employee type');
  }
}
```

**Problems:**
- Function does multiple things (violates SRP)
- Adding new types requires modifying this function
- Logic is exposed at high level

### ✅ Good: Use Polymorphism

```typescript
// Strategy pattern - each type handles its own logic
interface PayCalculator {
  calculate(): number;
}

class FullTimeEmployee implements PayCalculator {
  calculate() { return this.salary / 12; }
}

class PartTimeEmployee implements PayCalculator {
  calculate() { return this.hours * this.rate; }
}

// High-level function is now clean
function calculatePay(employee: PayCalculator): number {
  return employee.calculate();
}
```

### When to Bury the Switch

| Situation | Action |
|-----------|--------|
| Switch with 2 cases | Keep inline (KISS) |
| Switch with 3+ cases | Consider extracting |
| Switch likely to grow | Use polymorphism/strategy |
| Switch used in multiple places | Definitely extract |

> **Rule:** If a switch makes your function do multiple things, bury it.

---

## One Level of Abstraction (Step-Down Rule)

> **Tip:** Mixing high level and low level is always confusing and obscures the code's intent.

### The Problem: Mixed Abstraction Levels

When a function contains both orchestration logic AND implementation details, it becomes hard to understand. The reader can't tell what the function is trying to accomplish.

### ✅ Good: Step-Down Rule

Code should read like a story from top to bottom, with each function calling others at one level below.

```typescript
// HIGH LEVEL - What we're doing (Strategy)
function executeAnnualStrategy() {
  expandToEurope();
  optimizeSupplyChain();
}

// MID LEVEL - How we do it (Tactics)
function expandToEurope() {
  hireRegionalTeam();
  secureOfficeSpace();
  establishLocalPartnerships();
}

// LOW LEVEL - The actual work (Implementation)
function hireRegionalTeam() {
  const candidates = fetchCandidatesFromAPI();
  const filtered = candidates.filter(c => c.region === 'EU');
  return processHiring(filtered);
}
```

### ❌ Bad: Mixed Levels

```typescript
function executeAnnualStrategy() {
  // HIGH: Strategic decision
  expandToEurope();
  
  // LOW: Suddenly implementation details!
  const suppliers = await fetch('/api/suppliers');
  const filtered = suppliers.filter(s => s.rating > 4);
  await db.suppliers.updateMany(filtered);
  
  // HIGH: Back to strategy
  launchNewProduct();
}
```

### Quick Test

| Question | If Yes... |
|----------|-----------|
| Does your function have both "what" and "how"? | Split it |
| Are some lines orchestrating while others manipulate data? | Extract the data work |
| Would a non-developer understand the high-level flow? | You're doing it right |

> **Rule:** High-level functions should only call other high-level functions.

---

## Replace Comments with Functions

> **Tip:** If your function is divided into sections like "initialization" or "logic", it's doing more than one thing.

### Bad Smell: Section Comments

When you see comments like these inside a function, it's a sign the function should be split:

```typescript
// ❌ BAD: Section comments = doing too many things
function processOrder(order: Order) {
  // Validation
  if (!order.id) throw new Error("Invalid order");
  if (!order.items.length) throw new Error("Empty order");
  
  // Calculation
  const subtotal = order.items.reduce((sum, item) => sum + item.price, 0);
  const tax = subtotal * 0.1;
  const total = subtotal + tax;
  
  // Storage
  db.orders.save({ ...order, total });
  
  // Notification
  email.send(order.customer, `Order ${order.id} confirmed: $${total}`);
}
```

### ✅ Good: Extract to Named Functions

```typescript
// ✅ GOOD: Each step is a function, reads like a story
function processOrder(order: Order) {
  validateOrder(order);
  const total = calculateTotal(order);
  saveOrder(order, total);
  sendConfirmation(order, total);
}
```

**Benefits:**
- Code tells a story naturally
- Each function does ONE thing
- Self-documenting (no comments needed)
- Easy to test individual pieces

### The Rule

| If you have... | Then... |
|----------------|---------|
| `// Validation` section | Extract `validateX()` |
| `// Calculate` section | Extract `calculateX()` |
| `// Save/Store` section | Extract `saveX()` |
| `// Send/Notify` section | Extract `notifyX()` |

> **Rule:** Replace section comments with function names. The function name IS the comment.

---

## Extract Method Refactoring

> **Tip:** Massive functions bury business logic under low-level details. Extracting them makes your code document itself.

### ❌ Before: Massive Function

```typescript
function processOrder(order: Order) {
  // Get items from database
  const items = Database.getItems(order.itemIds);
  
  // Validate stock
  for (const item of items) {
    if (item.stock < 1) {
      throw new Error(`${item.name} out of stock`);
    }
  }
  
  // Calculate subtotal
  let subtotal = 0;
  for (const item of items) {
    subtotal += item.price * item.quantity;
  }
  
  // Apply discount and tax
  const discount = subtotal > 100 ? subtotal * 0.1 : 0;
  const tax = (subtotal - discount) * 0.08;
  const finalBill = subtotal - discount + tax;
  
  // Charge payment
  try {
    Stripe.charge(order.token, finalBill);
  } catch (e) {
    throw new Error('Payment failed');
  }
}
```

**Problems:**
- Business logic buried under loops and try-catch
- Hard to see the overall flow
- Difficult to test individual pieces
- Changes require scrolling through 30+ lines

### ✅ After: Extracted Methods

```typescript
function processOrder(order: Order) {
  const items = fetchOrderItems(order);
  validateStockAvailability(items);
  
  const subtotal = calculateSubtotal(items);
  const discount = calculateDiscount(subtotal);
  const tax = calculateTax(subtotal, discount);
  const finalBill = subtotal - discount + tax;
  
  executePayment(order.token, finalBill);
}
```

**Benefits:**
- Business logic is crystal clear
- Each function has one responsibility
- Easy to test `calculateDiscount()` separately
- Code documents itself through function names

### When to Extract

| Signal | Action |
|--------|--------|
| Function > 20 lines | Extract logical chunks |
| Multiple loops | Each loop → separate function |
| Try-catch blocks | Extract the operation being attempted |
| Complex conditions | Extract to `isValidX()` or `shouldDoX()` |

> **Rule:** If you have to scroll to read a function, it's too long. Extract it.

---

## Code Structure

| Pattern | Apply |
|---------|-------|
| **Guard Clauses** | Early returns for edge cases |
| **Flat > Nested** | Avoid deep nesting (max 2 levels) |
| **Composition** | Small functions composed together |
| **Colocation** | Keep related code close |

---

## Error Handling (Clean Code Ch. 7)

> **Tip:** Error handling is important, but if it obscures logic, it's wrong.

### Use Exceptions, Not Error Codes

```typescript
// ❌ Bad: Error codes
function getUser(id: string): User | null {
  const user = db.find(id);
  if (!user) return null;  // Caller might forget to check!
  return user;
}

// ✅ Good: Exceptions
function getUser(id: string): User {
  const user = db.find(id);
  if (!user) throw new UserNotFoundError(id);
  return user;
}
```

### Don't Return Null

```typescript
// ❌ Bad: Returns null
function getUsers(): User[] | null {
  return users.length > 0 ? users : null;
}

// Later...
const users = getUsers();
if (users !== null) {  // Easy to forget!
  users.forEach(...);
}

// ✅ Good: Return empty collection
function getUsers(): User[] {
  return users;  // Empty array if no users
}

// No null check needed
getUsers().forEach(...);
```

### Provide Context in Exceptions

```typescript
// ❌ Bad: No context
throw new Error("Operation failed");

// ✅ Good: Full context
throw new Error(`Failed to process order ${orderId} for user ${userId}: ${reason}`);
```

### Error Handling Quick Rules

| Rule | Description |
|------|-------------|
| **Fail Fast** | Detect and throw errors early |
| **Don't Swallow** | Never `catch` and ignore |
| **Log or Throw** | Don't do both |
| **Custom Errors** | Create specific error types |
| **Wrap External** | Wrap 3rd-party errors |

> **Rule:** Don't return null. Don't pass null. Throw meaningful exceptions.

---

## Comments Done Right (Clean Code Ch. 4)

> **Tip:** The proper use of comments is to compensate for our failure to express ourselves in code.

### Good Comments (Rare)

| Type | Example |
|------|---------|
| **Legal** | Copyright headers |
| **Informative** | `// Format: YYYY-MM-DD` |
| **Intent** | `// Using bubble sort for small n (faster for n < 10)` |
| **Warning** | `// WARNING: Not thread-safe!` |
| **TODO** | `// TODO: Refactor after deadline` |
| **API Docs** | JSDoc for public APIs |

### Bad Comments (Delete These)

```typescript
// ❌ Redundant - code already says this
// Increment counter
counter++;

// ❌ Journal comments - that's what git is for
// 2024-01-15 - John added validation
// 2024-01-20 - Jane fixed bug

// ❌ Commented-out code - delete it!
// function oldImplementation() { ... }

// ❌ Noise comments
// Constructor
constructor() { }

// ❌ Position markers
// ///////////// HELPER FUNCTIONS /////////////
```

### The Comment Test

Before writing a comment, ask:
1. Can I rename to make this clear?
2. Can I extract to a well-named function?
3. Is this comment temporary (TODO)?

> **Rule:** If you can express it in code, don't use a comment.

---

## SOLID Principles (Robert C. Martin)

> **Tip:** SOLID makes code flexible, maintainable, and understandable.

### S - Single Responsibility (Already covered)

Each class/function has ONE reason to change.

### O - Open/Closed Principle

```typescript
// ❌ Bad: Modify every time you add a new shape
function calculateArea(shape: Shape) {
  if (shape.type === 'circle') return Math.PI * shape.radius ** 2;
  if (shape.type === 'rectangle') return shape.width * shape.height;
  // Add more shapes = more if statements
}

// ✅ Good: Open for extension, closed for modification
interface Shape {
  calculateArea(): number;
}

class Circle implements Shape {
  calculateArea() { return Math.PI * this.radius ** 2; }
}

class Rectangle implements Shape {
  calculateArea() { return this.width * this.height; }
}

// Adding new shapes doesn't modify existing code
```

### L - Liskov Substitution Principle

```typescript
// ❌ Bad: Square violates Rectangle behavior
class Rectangle {
  setWidth(w: number) { this.width = w; }
  setHeight(h: number) { this.height = h; }
}

class Square extends Rectangle {
  setWidth(w: number) { 
    this.width = w; 
    this.height = w;  // Violates LSP!
  }
}

// ✅ Good: Separate abstractions
interface Shape {
  getArea(): number;
}

class Rectangle implements Shape { ... }
class Square implements Shape { ... }
```

### I - Interface Segregation Principle

```typescript
// ❌ Bad: Fat interface
interface Worker {
  work(): void;
  eat(): void;
  sleep(): void;
}

// Robot can't eat or sleep!
class Robot implements Worker { ... }

// ✅ Good: Segregated interfaces
interface Workable { work(): void; }
interface Feedable { eat(): void; }
interface Sleepable { sleep(): void; }

class Human implements Workable, Feedable, Sleepable { ... }
class Robot implements Workable { ... }
```

### D - Dependency Inversion Principle

```typescript
// ❌ Bad: High-level depends on low-level
class OrderService {
  private database = new MySQLDatabase();  // Tightly coupled!
}

// ✅ Good: Both depend on abstraction
interface Database {
  save(data: any): void;
}

class OrderService {
  constructor(private database: Database) {}  // Inject!
}
```

### SOLID Quick Reference

| Principle | One-liner |
|-----------|-----------|
| **S** | One class = one reason to change |
| **O** | Add features without modifying existing code |
| **L** | Subtypes must behave like their parent |
| **I** | Many small interfaces > one fat interface |
| **D** | Depend on abstractions, not concretions |

---

## Class Design (Clean Code Ch. 10)

> **Tip:** Classes should be small, focused, and cohesive.

### Small Classes

| Metric | Target |
|--------|--------|
| Lines | < 200 lines |
| Methods | < 10 public methods |
| Responsibilities | 1 (SRP) |
| Dependencies | < 5 injected |

### High Cohesion

```typescript
// ❌ Bad: Low cohesion - methods don't use same fields
class Employee {
  private name: string;
  private salary: number;
  private reports: Report[];
  
  getName() { return this.name; }
  calculatePay() { return this.salary / 12; }
  generateReport() { return this.reports.map(...); }  // Different concern!
}

// ✅ Good: High cohesion - methods work together
class Employee {
  private name: string;
  private hourlyRate: number;
  private hoursWorked: number;
  
  getName() { return this.name; }
  getHourlyRate() { return this.hourlyRate; }
  calculatePay() { return this.hourlyRate * this.hoursWorked; }
}

class ReportGenerator {
  generateEmployeeReport(employee: Employee) { ... }
}
```

### Encapsulation

| Rule | Description |
|------|-------------|
| **Private by default** | Only expose what's needed |
| **No public fields** | Use getters if needed |
| **Immutable when possible** | `readonly` fields |
| **Hide implementation** | Expose behavior, not data |

> **Rule:** A class should be so focused that you can describe it without using "and" or "or".

---

## Code Formatting (Clean Code Ch. 5)

> **Tip:** Code formatting is about communication, and communication is the professional developer's first priority.

### Vertical Formatting

| Rule | Description |
|------|-------------|
| **File length** | Aim for < 500 lines |
| **Related together** | Group related functions |
| **Caller above callee** | High-level functions at top |
| **Blank lines** | Separate concepts |
| **No long files** | Split if too long |

```typescript
// ✅ Good: Newspaper metaphor
// High-level (the headline)
export function processOrder(order: Order) {
  validateOrder(order);
  calculateTotal(order);
  chargePayment(order);
}

// Mid-level (the synopsis)
function validateOrder(order: Order) { ... }
function calculateTotal(order: Order) { ... }
function chargePayment(order: Order) { ... }

// Low-level (the details)
function validateItemStock(item: Item) { ... }
function applyDiscount(amount: number) { ... }
```

### Horizontal Formatting

| Rule | Target |
|------|--------|
| **Line length** | < 120 characters |
| **Indentation** | 2 or 4 spaces (team standard) |
| **Spaces around operators** | `a + b` not `a+b` |
| **No trailing whitespace** | Configure editor |

### Team Rules

| Rule | Description |
|------|-------------|
| **Pick one style** | ESLint/Prettier config |
| **Everyone follows** | No personal preferences |
| **Automate** | Format on save |
| **Review format separately** | Don't mix with logic changes |

> **Rule:** The team decides on formatting rules. Then everyone follows them.

---

## Testing Principles (Clean Code Ch. 9)

> **Tip:** Test code is just as important as production code.

### F.I.R.S.T. Principles

| Principle | Rule |
|-----------|------|
| **Fast** | Tests should run in milliseconds |
| **Independent** | Tests don't depend on each other |
| **Repeatable** | Same result every time, any environment |
| **Self-validating** | Pass or fail, no manual checking |
| **Timely** | Write tests before/with production code |

### One Concept Per Test

```typescript
// ❌ Bad: Multiple concepts
test('user operations', () => {
  const user = createUser('John');
  expect(user.name).toBe('John');
  
  user.updateEmail('john@example.com');
  expect(user.email).toBe('john@example.com');
  
  user.deactivate();
  expect(user.isActive).toBe(false);
});

// ✅ Good: One concept each
test('createUser sets name correctly', () => {
  const user = createUser('John');
  expect(user.name).toBe('John');
});

test('updateEmail changes email', () => {
  const user = createUser('John');
  user.updateEmail('john@example.com');
  expect(user.email).toBe('john@example.com');
});

test('deactivate sets isActive to false', () => {
  const user = createUser('John');
  user.deactivate();
  expect(user.isActive).toBe(false);
});
```

### Test Naming

```typescript
// ✅ Good: Describes scenario and expectation
'should_ThrowError_When_EmailIsInvalid'
'calculateTotal_WithDiscount_ReturnsReducedPrice'
'user cannot login with wrong password'
```

### AAA Pattern

```typescript
test('calculateDiscount applies 10% for orders over $100', () => {
  // Arrange
  const order = createOrder({ total: 150 });
  
  // Act
  const discount = calculateDiscount(order);
  
  // Assert
  expect(discount).toBe(15);
});
```

### Testing Quick Rules

| Rule | Description |
|------|-------------|
| **Test behavior** | Not implementation |
| **No logic in tests** | No if/loops in tests |
| **Minimal setup** | Only what's needed |
| **Readable failures** | Clear assertion messages |
| **Don't test framework** | Only test YOUR code |

> **Rule:** If you let tests rot, your code will rot too.

---

## TypeScript Best Practices

> **Tip:** TypeScript's power comes from its type system. Use it properly.

### Type Everything

```typescript
// ❌ Bad: Implicit any
function processData(data) {
  return data.map(item => item.value);
}

// ✅ Good: Explicit types
function processData(data: DataItem[]): number[] {
  return data.map(item => item.value);
}
```

### Avoid `any`, Use `unknown`

```typescript
// ❌ Bad: any bypasses type checking
function parseJSON(json: string): any {
  return JSON.parse(json);
}

// ✅ Good: unknown requires type checking
function parseJSON(json: string): unknown {
  return JSON.parse(json);
}

// Must narrow type before using
const result = parseJSON(data);
if (isUser(result)) {
  console.log(result.name);  // Type-safe!
}
```

### Interfaces vs Types

| Use Case | Prefer |
|----------|--------|
| Object shapes | `interface` |
| Union types | `type` |
| Function signatures | `type` |
| Extendable contracts | `interface` |
| Computed properties | `type` |

```typescript
// ✅ Interface for objects
interface User {
  id: string;
  name: string;
}

// ✅ Type for unions
type Status = 'pending' | 'active' | 'inactive';

// ✅ Type for functions
type Handler = (event: Event) => void;
```

### Strict Null Checks

```typescript
// ❌ Bad: Nullable not handled
function getUser(id: string): User {
  return users.find(u => u.id === id);  // Could be undefined!
}

// ✅ Good: Handle nullable
function getUser(id: string): User | undefined {
  return users.find(u => u.id === id);
}

// Or throw if required
function getUserOrThrow(id: string): User {
  const user = users.find(u => u.id === id);
  if (!user) throw new UserNotFoundError(id);
  return user;
}
```

### TypeScript Quick Rules

| Rule | Description |
|------|-------------|
| **Explicit return types** | Always for public functions |
| **No `any`** | Use `unknown` and narrow |
| **Strict mode** | Enable all strict flags |
| **Readonly** | Use for immutable data |
| **Enums** | Use const enums or string unions |

> **Rule:** If TypeScript can't catch the bug, you wrote bad types.

---

## Security Patterns

> **Tip:** Security is not a feature, it's a requirement.

### Input Validation

```typescript
// ❌ Bad: Trust user input
function createUser(data: any) {
  db.users.create(data);
}

// ✅ Good: Validate everything
function createUser(data: unknown) {
  const validated = userSchema.parse(data);  // Zod/Joi
  db.users.create(validated);
}
```

### Sanitize Logs

```typescript
// ❌ Bad: Sensitive data in logs
console.log('User login:', { email, password, token });

// ✅ Good: Mask sensitive data
console.log('User login:', { 
  email, 
  password: '***REDACTED***',
  token: token.slice(0, 8) + '...'
});
```

### SQL Injection Prevention

```typescript
// ❌ Bad: String concatenation
const query = `SELECT * FROM users WHERE id = '${userId}'`;

// ✅ Good: Parameterized queries
const user = await prisma.user.findUnique({ where: { id: userId } });

// ✅ Good: Raw with parameters
const users = await prisma.$queryRaw`
  SELECT * FROM users WHERE id = ${userId}
`;
```

### Security Quick Rules

| Rule | Description |
|------|-------------|
| **Validate all inputs** | Never trust client data |
| **Escape outputs** | Prevent XSS |
| **Use parameterized queries** | Prevent SQL injection |
| **Hash passwords** | Use bcrypt/argon2 |
| **Rate limiting** | Prevent brute force |
| **HTTPS only** | In production |
| **Sanitize logs** | No passwords/tokens |
| **Principle of least privilege** | Minimal permissions |

> **Rule:** Assume all input is malicious until validated.

---

## Performance Patterns

> **Tip:** Measure first, optimize second.

### Caching

```typescript
// ❌ Bad: Fetch every time
async function getUser(id: string) {
  return db.users.findUnique({ where: { id } });
}

// ✅ Good: Cache expensive operations
const userCache = new Map<string, User>();

async function getUser(id: string) {
  if (userCache.has(id)) {
    return userCache.get(id)!;
  }
  const user = await db.users.findUnique({ where: { id } });
  if (user) userCache.set(id, user);
  return user;
}
```

### Pagination

```typescript
// ❌ Bad: Return all records
async function getUsers() {
  return db.users.findMany();  // Could be millions!
}

// ✅ Good: Paginate
async function getUsers(page: number, limit: number = 20) {
  return db.users.findMany({
    skip: (page - 1) * limit,
    take: limit,
  });
}
```

### Database Query Optimization

```typescript
// ❌ Bad: N+1 query problem
const orders = await db.orders.findMany();
for (const order of orders) {
  order.user = await db.users.findUnique({ where: { id: order.userId } });
}

// ✅ Good: Include relations
const orders = await db.orders.findMany({
  include: { user: true }
});

// ✅ Good: Select only needed fields
const users = await db.users.findMany({
  select: { id: true, name: true }  // Not all 50 columns
});
```

### Performance Quick Rules

| Rule | Description |
|------|-------------|
| **Paginate** | Never return unbounded lists |
| **Index queries** | Index WHERE/ORDER BY columns |
| **Avoid N+1** | Use includes/joins |
| **Select specific fields** | Don't SELECT * |
| **Cache wisely** | Cache reads, invalidate on writes |
| **Lazy load** | Load data only when needed |
| **Compress responses** | Use gzip/brotli |
| **Connection pooling** | Reuse database connections |

> **Rule:** Premature optimization is evil, but knowing patterns is wisdom.

---

## AI Coding Style

| Situation | Action |
|-----------|--------|
| User asks for feature | Write it directly |
| User reports bug | Fix it, don't explain |
| No clear requirement | Ask, don't assume |

---

## Anti-Patterns (DON'T)

| ❌ Pattern | ✅ Fix |
|-----------|-------|
| Comment every line | Delete obvious comments |
| Helper for one-liner | Inline the code |
| Factory for 2 objects | Direct instantiation |
| utils.ts with 1 function | Put code where used |
| "First we import..." | Just write code |
| Deep nesting | Guard clauses |
| Magic numbers | Named constants |
| God functions | Split by responsibility |

---

## 🔴 Before Editing ANY File (THINK FIRST!)

**Before changing a file, ask yourself:**

| Question | Why |
|----------|-----|
| **What imports this file?** | They might break |
| **What does this file import?** | Interface changes |
| **What tests cover this?** | Tests might fail |
| **Is this a shared component?** | Multiple places affected |

**Quick Check:**
```
File to edit: UserService.ts
└── Who imports this? → UserController.ts, AuthController.ts
└── Do they need changes too? → Check function signatures
```

> 🔴 **Rule:** Edit the file + all dependent files in the SAME task.
> 🔴 **Never leave broken imports or missing updates.**

---

## Summary

| Do | Don't |
|----|-------|
| Write code directly | Write tutorials |
| Let code self-document | Add obvious comments |
| Fix bugs immediately | Explain the fix first |
| Inline small things | Create unnecessary files |
| Name things clearly | Use abbreviations |
| Keep functions small | Write 100+ line functions |

> **Remember: The user wants working code, not a programming lesson.**

---

## 🔴 Self-Check Before Completing (MANDATORY)

**Before saying "task complete", verify:**

| Check | Question |
|-------|----------|
| ✅ **Goal met?** | Did I do exactly what user asked? |
| ✅ **Files edited?** | Did I modify all necessary files? |
| ✅ **Code works?** | Did I test/verify the change? |
| ✅ **No errors?** | Lint and TypeScript pass? |
| ✅ **Nothing forgotten?** | Any edge cases missed? |

> 🔴 **Rule:** If ANY check fails, fix it before completing.

---

## Verification Scripts (MANDATORY)

> 🔴 **CRITICAL:** Each agent runs ONLY their own skill's scripts after completing work.

### Agent → Script Mapping

| Agent | Script | Command |
|-------|--------|---------|
| **frontend-specialist** | UX Audit | `python ~/.claude/skills/frontend-design/scripts/ux_audit.py .` |
| **frontend-specialist** | A11y Check | `python ~/.claude/skills/frontend-design/scripts/accessibility_checker.py .` |
| **backend-specialist** | API Validator | `python ~/.claude/skills/api-patterns/scripts/api_validator.py .` |
| **mobile-developer** | Mobile Audit | `python ~/.claude/skills/mobile-design/scripts/mobile_audit.py .` |
| **database-architect** | Schema Validate | `python ~/.claude/skills/database-design/scripts/schema_validator.py .` |
| **security-auditor** | Security Scan | `python ~/.claude/skills/vulnerability-scanner/scripts/security_scan.py .` |
| **seo-specialist** | SEO Check | `python ~/.claude/skills/seo-fundamentals/scripts/seo_checker.py .` |
| **seo-specialist** | GEO Check | `python ~/.claude/skills/geo-fundamentals/scripts/geo_checker.py .` |
| **performance-optimizer** | Lighthouse | `python ~/.claude/skills/performance-profiling/scripts/lighthouse_audit.py <url>` |
| **test-engineer** | Test Runner | `python ~/.claude/skills/testing-patterns/scripts/test_runner.py .` |
| **test-engineer** | Playwright | `python ~/.claude/skills/webapp-testing/scripts/playwright_runner.py <url>` |
| **Any agent** | Lint Check | `python ~/.claude/skills/lint-and-validate/scripts/lint_runner.py .` |
| **Any agent** | Type Coverage | `python ~/.claude/skills/lint-and-validate/scripts/type_coverage.py .` |
| **Any agent** | i18n Check | `python ~/.claude/skills/i18n-localization/scripts/i18n_checker.py .` |

> ❌ **WRONG:** `test-engineer` running `ux_audit.py`
> ✅ **CORRECT:** `frontend-specialist` running `ux_audit.py`

---

### 🔴 Script Output Handling (READ → SUMMARIZE → ASK)

**When running a validation script, you MUST:**

1. **Run the script** and capture ALL output
2. **Parse the output** - identify errors, warnings, and passes
3. **Summarize to user** in this format:

```markdown
## Script Results: [script_name.py]

### ❌ Errors Found (X items)
- [File:Line] Error description 1
- [File:Line] Error description 2

### ⚠️ Warnings (Y items)
- [File:Line] Warning description

### ✅ Passed (Z items)
- Check 1 passed
- Check 2 passed

**Should I fix the X errors?**
```

4. **Wait for user confirmation** before fixing
5. **After fixing** → Re-run script to confirm

> 🔴 **VIOLATION:** Running script and ignoring output = FAILED task.
> 🔴 **VIOLATION:** Auto-fixing without asking = Not allowed.
> 🔴 **Rule:** Always READ output → SUMMARIZE → ASK → then fix.

