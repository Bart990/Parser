// Александр Сергеевич, младшее поколение очень соскучилось по перекурам
/*
Программа принимает на вход:
  1) Путь к файлу с исходным кодом на Java.
  2) Название класса, для которого требуется сгенерировать методы (либо ключевое слово ALL, чтобы сгенерировать методы для всех классов в файле).
  3) Флаги командной строки (необязательные), определяющие политику замены методов:
     -replaceEquals       : если найден метод equals, заменить его
     -replaceHashCode     : если найден метод hashCode, заменить его
     -replaceGetters      : если найден любой геттер, заменить его
     -replaceSetters      : если найден любой сеттер, заменить его
     -replaceConstructors : если найден любой конструктор, заменить его

Задача:
  1) Прочитать исходный файл.
  2) Найти указанный класс (или все классы, если передано "ALL").
  3) Найти все поля класса (без учета static и final).
  4) Проверить существование геттеров, сеттеров, equals, hashCode и конструкторов:
       - Для геттеров/сеттеров: анализируем название методов и соответствие типу и имени поля.
       - Для equals/hashCode: ищем сигнатуры "public boolean equals(Object o)" / "public int hashCode()".
       - Для конструкторов: ищем сигнатуры вида "public ClassName(...)".
  5) В зависимости от флагов командной строки:
       - Если флаг -replace* указан для соответствующего метода, и метод уже существует, удалить/заменить его.
       - Если метода не существует, сгенерировать его.
  6) Сформировать новый текст, записать его в выходной файл (можно перезаписать исходный или создать новый).

Подход:
  - Упрощенная реализация парсинга кода на Java (на практике лучше использовать полноценный парсер,
    например, JavaParser или другие инструменты, но здесь для наглядности применим регулярные выражения
    и примитивный синтаксический разбор).
  - Генерация методов (get, set, equals, hashCode, конструкторы) через шаблоны строк.
  - Обработка нескольких классов в одном файле.

Тестовые примеры использования (предполагаем, что всё компилируется и запускается командой):
  1) java CodeModifier MyFile.java MyClass
     => генерирует методы, которых нет, без замены существующих.
  2) java CodeModifier MyFile.java MyClass -replaceEquals -replaceHashCode
     => если в MyClass уже есть equals и hashCode, они будут перезаписаны; все остальные методы (getters/setters/конструкторы)
        будут сгенерированы только при отсутствии.
  3) java CodeModifier MyFile.java ALL -replaceSetters
     => для всех классов в файле, если сеттер уже есть, он будет заменён, остальные методы генерируются только при отсутствии.
*/

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class CodeModifier {
    // Ищем объявление класса вида:
    // "public class SomeClass", "class SomeClass", "private class SomeClass" и т.д.
    // Без учёта abstract, final, и прочих специфик, для упрощения.
    private static final Pattern CLASS_PATTERN =
            Pattern.compile("(\\b(public|private|protected)?\\s*(static)?\\s*class\\s+(\\w+))");

    // Ищем поля вида:
    // [модификаторы] тип имяПоля;
    // Упрощённо, предполагаем что в одной строке объявлен только один атрибут
    // и что имяПоля не содержит символы вроде <T>, [ ], т.п.
    // Пример: private int age; public String name;
    // Исключим static и final, т.к. обычно геттеры/сеттеры к ним не нужны.
    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\\b(private|protected|public)?\\s*(?!static|final)(\\w+)\\s+(\\w+)\\s*;");

    // Для нахождения сигнатур (упрощённо) equals, hashCode, getters, setters и конструкторов:
    private static final Pattern EQUALS_PATTERN =
            Pattern.compile("\\bpublic\\s+boolean\\s+equals\\s*\\(\\s*Object\\s+\\w+\\)");
    private static final Pattern HASHCODE_PATTERN =
            Pattern.compile("\\bpublic\\s+int\\s+hashCode\\s*\\(");

    // Ищем конструктор вида: public ClassName(...) { ... }
    // Упрощённо ищем "public НазваниеКласса("
    private static final String CONSTRUCTOR_REGEX_TEMPLATE = "\\bpublic\\s+%s\\s*\\(";

    // Упрощённое определение геттеров/сеттеров:
    //  - Геттер: public [Type] getXxx() { ... }
    //  - Сеттер: public void setXxx([Type] value) { ... }
    private static final String GETTER_REGEX_TEMPLATE =
            "\\bpublic\\s+%s\\s+get%s\\s*\\(";
    private static final String SETTER_REGEX_TEMPLATE =
            "\\bpublic\\s+void\\s+set%s\\s*\\(\\s*%s\\s+\\w+\\)";

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Использование: java CodeModifier <путь к файлу> <имя класса | ALL> [флаги]");
            System.out.println("Пример: java CodeModifier MyFile.java MyClass -replaceEquals -replaceHashCode");
            return;
        }

        String filePath = args[0];
        String targetClass = args[1];

        // Считываем остальные флаги
        Set<String> flags = new HashSet<>();
        for (int i = 2; i < args.length; i++) {
            flags.add(args[i]);
        }

        // Прочитаем исходный код целиком
        String sourceCode = new String(Files.readAllBytes(Paths.get(filePath)));

        // Разбиваем код на строки для удобства (хотя мы будем работать и со всем текстом)
        List<String> lines = Arrays.asList(sourceCode.split("\\r?\\n"));

        // Собираем все описания классов
        List<ClassInfo> classes = parseClasses(sourceCode);

        // Если targetClass = "ALL", обрабатываем все классы, иначе – только тот, что указан
        if ("ALL".equalsIgnoreCase(targetClass)) {
            for (ClassInfo c : classes) {
                sourceCode = processClass(sourceCode, c.className, c.startIndex, c.endIndex, flags);
            }
        } else {
            // Найдём класс по имени
            ClassInfo targetClassInfo = null;
            for (ClassInfo c : classes) {
                if (c.className.equals(targetClass)) {
                    targetClassInfo = c;
                    break;
                }
            }
            if (targetClassInfo == null) {
                System.out.println("Класс " + targetClass + " не найден в файле.");
                return;
            }
            sourceCode = processClass(
                    sourceCode,
                    targetClassInfo.className,
                    targetClassInfo.startIndex,
                    targetClassInfo.endIndex,
                    flags
            );
        }

        // Сохраняем результат (для наглядности перезапишем файл)
        Files.write(Paths.get(filePath), sourceCode.getBytes());
        System.out.println("Генерация/обновление методов завершена. Результат сохранён в " + filePath);
    }

    /**
     * Парсит все классы в исходном коде, возвращает список ClassInfo,
     * где хранится имя класса, а также индексы начала и конца блока (по символам).
     */
    private static List<ClassInfo> parseClasses(String sourceCode) {
        List<ClassInfo> result = new ArrayList<>();

        Matcher classMatcher = CLASS_PATTERN.matcher(sourceCode);
        while (classMatcher.find()) {
            String classDeclaration = classMatcher.group(1); // вся строка объявления (например: "public class MyClass")
            String className = classMatcher.group(4);        // конкретно имя класса

            // Ищем начало блока { ... } сразу после объявленя класса
            int classBodyStartIndex = sourceCode.indexOf("{", classMatcher.end());
            if (classBodyStartIndex == -1) {
                // Если не нашли {, странная ситуация, пропускаем
                continue;
            }
            // Нужно найти соответствующую закрывающую скобку. Будем считать скобки.
            int braceCount = 0;
            int endIndex = -1;
            for (int i = classBodyStartIndex; i < sourceCode.length(); i++) {
                char ch = sourceCode.charAt(i);
                if (ch == '{') braceCount++;
                if (ch == '}') braceCount--;
                if (braceCount == 0) {
                    endIndex = i;
                    break;
                }
            }
            if (endIndex != -1) {
                // У нас есть блок класса [classBodyStartIndex, endIndex]
                // Сохраняем в список
                result.add(new ClassInfo(className, classBodyStartIndex, endIndex));
            }
        }

        return result;
    }

    /**
     * Обрабатывает один класс (добавляет/заменяет методы в нём).
     *
     * @param fullSource полный текст исходного кода
     * @param className  имя класса
     * @param classStart индекс '{' класса
     * @param classEnd   индекс '}' (последней закрывающей скобки класса)
     * @param flags      набор флагов для замены
     * @return изменённый текст кода
     */
    private static String processClass(String fullSource, String className, int classStart, int classEnd, Set<String> flags) {
        // Вырезаем тело класса:
        String classBody = fullSource.substring(classStart + 1, classEnd); // содержимое без фигурных скобок

        // Собираем информацию о полях класса
        List<FieldInfo> fields = parseFields(classBody);

        // Удалим/заменим при необходимости методы equals, hashCode, get, set, конструкторы
        classBody = maybeRemoveExistingMethod(classBody, EQUALS_PATTERN, flags.contains("-replaceEquals"));
        classBody = maybeRemoveExistingMethod(classBody, HASHCODE_PATTERN, flags.contains("-replaceHashCode"));

        // Удаляем/заменяем конструкторы (если нужно)
        Pattern constructorPattern = Pattern.compile(String.format(CONSTRUCTOR_REGEX_TEMPLATE, className));
        classBody = maybeRemoveExistingMethod(classBody, constructorPattern, flags.contains("-replaceConstructors"));

        // Для каждого поля проверяем наличие геттера/сеттера, при необходимости удаляем.
        List<String> methodsToAdd = new ArrayList<>();
        for (FieldInfo field : fields) {
            // Проверяем наличие геттера
            // Например, если поле name => getName(). Учитываем, что первая буква поля может быть в нижнем регистре.
            // Геттер должен возвращать тип поля.
            String capitalizedName = capitalize(field.fieldName);
            String getterRegex = String.format(GETTER_REGEX_TEMPLATE, Pattern.quote(field.fieldType), capitalizedName);
            Pattern getterPattern = Pattern.compile(getterRegex);

            String setterRegex = String.format(SETTER_REGEX_TEMPLATE, capitalizedName, Pattern.quote(field.fieldType));
            Pattern setterPattern = Pattern.compile(setterRegex);

            // Удаляем геттер, если нужно
            classBody = maybeRemoveExistingMethod(classBody, getterPattern, flags.contains("-replaceGetters"));
            // Удаляем сеттер, если нужно
            classBody = maybeRemoveExistingMethod(classBody, setterPattern, flags.contains("-replaceSetters"));
        }

        // Теперь генерируем необходимые методы, которых нет.
        // 1. equals
        if (!methodExists(classBody, EQUALS_PATTERN)) {
            classBody = classBody + "\n" + generateEqualsMethod(className, fields);
        }
        // 2. hashCode
        if (!methodExists(classBody, HASHCODE_PATTERN)) {
            classBody = classBody + "\n" + generateHashCodeMethod(fields);
        }
        // 3. Конструктор (дефолтный) – если нет ни одного конструктора
        //    В нашем упрощении, предположим, что если в классе нет ни одного конструктора, мы добавим
        //    два типа конструкторов: пустой (дефолтный) и со всеми полями.
        if (!methodExists(classBody, constructorPattern)) {
            classBody = classBody + "\n" + generateDefaultConstructor(className);
            classBody = classBody + "\n" + generateFullConstructor(className, fields);
        }

        // 4. геттеры/сеттеры для всех полей (если их нет)
        for (FieldInfo field : fields) {
            // Проверим геттер
            Pattern getterPattern = Pattern.compile(
                    String.format(GETTER_REGEX_TEMPLATE, Pattern.quote(field.fieldType), capitalize(field.fieldName))
            );
            if (!methodExists(classBody, getterPattern)) {
                classBody = classBody + "\n" + generateGetterMethod(field);
            }
            // Проверим сеттер
            Pattern setterPattern = Pattern.compile(
                    String.format(SETTER_REGEX_TEMPLATE, capitalize(field.fieldName), Pattern.quote(field.fieldType))
            );
            if (!methodExists(classBody, setterPattern)) {
                classBody = classBody + "\n" + generateSetterMethod(field);
            }
        }

        // Вставляем обновлённое тело обратно в общий текст
        // fullSource = до '{' + '{' + новый classBody + '}' + после '}'
        return fullSource.substring(0, classStart + 1)
                + classBody
                + fullSource.substring(classEnd);
    }

    /**
     * Упрощённая функция, которая удаляет или не трогает метод (equals, hashCode, или др.)
     * Если removeIfExists = true, метод будет удалён при обнаружении.
     */
    private static String maybeRemoveExistingMethod(String classBody, Pattern pattern, boolean removeIfExists) {
        if (!removeIfExists) {
            // Если не нужно удалять, сразу возвращаем.
            return classBody;
        }

        // Проще всего - найти все вхождения и вырезать блоки от "{" до парной "}".
        // Но методы могут содержать вложенные фигурные скобки (например, внутренние классы, анонимные и т.п.).
        // Упростим задачу, считая, что тело метода балансирует скобки, и не содержит класс в классе.
        // В реальной жизни нужно пользоваться полноценным парсером.

        Matcher m = pattern.matcher(classBody);
        StringBuffer sb = new StringBuffer();
        int lastEnd = 0;
        while (m.find()) {
            // Нашли сигнатуру метода. Найдём ближайшую '{' после неё.
            int methodStartIndex = m.start();
            int braceIndex = classBody.indexOf('{', methodStartIndex);
            if (braceIndex == -1) {
                // Странно, метод не имеет тела...
                // Просто пропускаем
                continue;
            }
            // Считаем баланс фигурных скобок от braceIndex
            int braceCount = 0;
            int methodEndIndex = -1;
            for (int i = braceIndex; i < classBody.length(); i++) {
                if (classBody.charAt(i) == '{') braceCount++;
                if (classBody.charAt(i) == '}') braceCount--;
                if (braceCount == 0) {
                    methodEndIndex = i;
                    break;
                }
            }

            if (methodEndIndex != -1) {
                // Удаляем блок [methodStartIndex, methodEndIndex]
                sb.append(classBody, lastEnd, methodStartIndex);
                lastEnd = methodEndIndex + 1;
            }
        }

        // Дописываем остаток
        sb.append(classBody.substring(lastEnd));

        return sb.toString();
    }

    /**
     * Проверяет, существует ли в тексте classBody метод, соответствующий паттерну (equals, hashCode, конструктор и т.п.)
     */
    private static boolean methodExists(String classBody, Pattern pattern) {
        Matcher m = pattern.matcher(classBody);
        return m.find();
    }

    /**
     * Парсинг полей класса на основе FIELD_PATTERN.
     * Исключаем static и final, но допускаем private/protected/public или их отсутствие.
     */
    private static List<FieldInfo> parseFields(String classBody) {
        List<FieldInfo> fields = new ArrayList<>();
        Matcher fieldMatcher = FIELD_PATTERN.matcher(classBody);
        while (fieldMatcher.find()) {
            // Пример групп:
            // group(1) = private | protected | public | (может быть null)
            // group(2) = тип (int, String и т.п.)
            // group(3) = имя (age, name и т.п.)
            String fieldType = fieldMatcher.group(2);
            String fieldName = fieldMatcher.group(3);

            // Добавляем в список
            fields.add(new FieldInfo(fieldType, fieldName));
        }
        return fields;
    }

    // Генерация equals
    private static String generateEqualsMethod(String className, List<FieldInfo> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    @Override\n");
        sb.append("    public boolean equals(Object o) {\n");
        sb.append("        if (this == o) return true;\n");
        sb.append("        if (o == null || getClass() != o.getClass()) return false;\n");
        sb.append("        ").append(className).append(" that = (").append(className).append(") o;\n");
        for (FieldInfo f : fields) {
            // Для простоты сравниваем объекты ссылочно или через equals для строк/объектов
            if (isPrimitiveOrWrapper(f.fieldType)) {
                // Для примитивов (int, double, boolean, etc. – с учётом, что в реале бывают нюансы)
                // Для double/float есть нюансы (сравнение по epsilon и т.д.), но упростим
                sb.append("        if (").append(f.fieldName).append(" != that.").append(f.fieldName).append(") return false;\n");
            } else {
                // Для объектов
                sb.append("        if (").append(f.fieldName)
                        .append(" != null ? !").append(f.fieldName).append(".equals(that.")
                        .append(f.fieldName).append(") : that.").append(f.fieldName).append(" != null) return false;\n");
            }
        }
        sb.append("        return true;\n");
        sb.append("    }\n");
        return sb.toString();
    }

    // Генерация hashCode
    private static String generateHashCodeMethod(List<FieldInfo> fields) {
        // Упрощённый вариант, где используем Objects.hash(...) (доступен с Java 7+)
        StringBuilder sb = new StringBuilder();
        sb.append("    @Override\n");
        sb.append("    public int hashCode() {\n");
        sb.append("        return java.util.Objects.hash(");
        for (int i = 0; i < fields.size(); i++) {
            sb.append(fields.get(i).fieldName);
            if (i < fields.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(");\n");
        sb.append("    }\n");
        return sb.toString();
    }

    // Генерация дефолтного конструктора
    private static String generateDefaultConstructor(String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append("() {\n");
        sb.append("    }\n");
        return sb.toString();
    }

    // Генерация конструктора со всеми полями
    private static String generateFullConstructor(String className, List<FieldInfo> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(className).append("(");
        for (int i = 0; i < fields.size(); i++) {
            FieldInfo f = fields.get(i);
            sb.append(f.fieldType).append(" ").append(f.fieldName);
            if (i < fields.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(") {\n");
        for (FieldInfo f : fields) {
            sb.append("        this.").append(f.fieldName).append(" = ").append(f.fieldName).append(";\n");
        }
        sb.append("    }\n");
        return sb.toString();
    }

    // Генерация геттера
    private static String generateGetterMethod(FieldInfo field) {
        String capitalizedName = capitalize(field.fieldName);
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(field.fieldType).append(" get").append(capitalizedName).append("() {\n");
        sb.append("        return ").append(field.fieldName).append(";\n");
        sb.append("    }\n");
        return sb.toString();
    }

    // Генерация сеттера
    private static String generateSetterMethod(FieldInfo field) {
        String capitalizedName = capitalize(field.fieldName);
        StringBuilder sb = new StringBuilder();
        sb.append("    public void set").append(capitalizedName)
                .append("(").append(field.fieldType).append(" ").append(field.fieldName).append(") {\n");
        sb.append("        this.").append(field.fieldName).append(" = ").append(field.fieldName).append(";\n");
        sb.append("    }\n");
        return sb.toString();
    }

    // Вспомогательный метод для капитализации первого символа
    private static String capitalize(String input) {
        if (input == null || input.isEmpty()) return input;
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    // Упрощённая проверка: является ли тип примитивным или "обёрткой"/String
    // (для equals сравниваем напрямую, иначе используем .equals)
    private static boolean isPrimitiveOrWrapper(String type) {
        // Упростим, считаем что все кроме int, long, double, boolean, float, char, byte, short,
        // и их обёртки + String надо сравнивать через .equals
        // В реальном случае можно расширять список.
        Set<String> set = new HashSet<>(Arrays.asList(
                "int", "long", "double", "float", "boolean", "char", "byte", "short",
                "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short",
                "String"
        ));
        return set.contains(type);
    }

    /**
     * Вспомогательный класс для хранения информации о поле.
     */
    private static class FieldInfo {
        String fieldType;
        String fieldName;

        FieldInfo(String fieldType, String fieldName) {
            this.fieldType = fieldType;
            this.fieldName = fieldName;
        }
    }

    /**
     * Вспомогательный класс для хранения информации о классе: имя и индексы его начала/конца.
     */
    private static class ClassInfo {
        String className;
        int startIndex;
        int endIndex;

        ClassInfo(String className, int startIndex, int endIndex) {
            this.className = className;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }
}
