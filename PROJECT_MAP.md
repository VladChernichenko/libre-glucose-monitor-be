# 🗺️ PROJECT MAP - Glucose Monitor System

## 📍 **Project Locations**

| Project | Path | Type | Description |
|---------|------|------|-------------|
| **Backend** | `~/IdeaProjects/glucose-monitor-be` | Spring Boot | Java backend with PostgreSQL, JPA, REST APIs |
| **Frontend** | `~/cursorai` | React + TypeScript | React frontend application |

## 🚀 **Quick Navigation Commands**

### **Go to Backend:**
```bash
cd ~/IdeaProjects/glucose-monitor-be
```

### **Go to Frontend:**
```bash
cd ~/cursorai
```

### **Check Current Location:**
```bash
pwd
ls -la  # Look for build.gradle (BE) or package.json (FE)
```

## 🔍 **How to Identify Which Project You're In**

### **Backend Project Indicators:**
- ✅ `build.gradle` file exists
- ✅ `src/main/java/` directory structure
- ✅ Java files (`.java` extension)
- ✅ `./gradlew` executable

### **Frontend Project Indicators:**
- ✅ `package.json` file exists
- ✅ `src/components/` directory structure
- ✅ TypeScript/React files (`.tsx`, `.ts` extension)
- ✅ `npm` or `yarn` commands work

## 📁 **Project Structure Overview**

### **Backend (`~/IdeaProjects/glucose-monitor-be`)**
```
├── build.gradle                    # Gradle build config
├── src/main/java/                 # Java source code
│   └── che/glucosemonitorbe/
│       ├── controller/            # REST controllers
│       ├── service/               # Business logic
│       ├── domain/                # JPA entities
│       ├── dto/                   # Data transfer objects
│       └── config/                # Configuration classes
├── src/main/resources/            # Application properties
└── ./gradlew                      # Gradle wrapper
```

### **Frontend (`~/cursorai`)**
```
├── package.json                   # Node.js dependencies
├── src/                          # React source code
│   ├── components/               # React components
│   ├── services/                 # API services
│   ├── hooks/                    # React hooks
│   └── types/                    # TypeScript types
├── public/                       # Static assets
└── npm start                     # Start development server
```

## ⚠️ **Common Confusion Points**

1. **Wrong Directory for Commands:**
   - ❌ Running `npm start` in backend directory
   - ❌ Running `./gradlew bootRun` in frontend directory

2. **File Operations:**
   - ❌ Creating frontend files in backend project
   - ❌ Creating backend files in frontend project

3. **Git Operations:**
   - ❌ Committing frontend changes to backend repo
   - ❌ Committing backend changes to frontend repo

## 🎯 **Best Practices**

1. **Always check `pwd` before operations**
2. **Look for project indicators** (`build.gradle` vs `package.json`)
3. **Use the navigation commands above**
4. **Keep this map handy** for quick reference

## 🔧 **Quick Setup Commands**

### **Start Backend:**
```bash
cd ~/IdeaProjects/glucose-monitor-be
./gradlew bootRun
```

### **Start Frontend:**
```bash
cd ~/cursorai
npm start
```

### **Check Both Status:**
```bash
# Backend status
cd ~/IdeaProjects/glucose-monitor-be && ./gradlew bootRun --status

# Frontend status
cd ~/cursorai && npm run build --dry-run
```

---
*Last Updated: $(date)*
*Keep this file in your home directory for quick reference!*

