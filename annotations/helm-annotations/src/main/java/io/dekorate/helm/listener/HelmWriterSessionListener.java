/**
 * Copyright 2018 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **/
package io.dekorate.helm.listener;

import static io.dekorate.helm.util.HelmConfigUtils.deductProperty;
import static io.dekorate.helm.util.HelmTarArchiver.createTarBall;
import static io.dekorate.helm.util.MapUtils.toMultiValueSortedMap;
import static io.dekorate.helm.util.MapUtils.toMultiValueUnsortedMap;
import static io.dekorate.helm.util.ValuesSchemaUtils.createSchema;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;

import io.dekorate.ConfigReference;
import io.dekorate.Logger;
import io.dekorate.LoggerFactory;
import io.dekorate.Session;
import io.dekorate.SessionListener;
import io.dekorate.WithProject;
import io.dekorate.WithSession;
import io.dekorate.helm.config.AddIfStatement;
import io.dekorate.helm.config.Annotation;
import io.dekorate.helm.config.HelmChartConfig;
import io.dekorate.helm.config.HelmExpression;
import io.dekorate.helm.config.ValueReference;
import io.dekorate.helm.model.Chart;
import io.dekorate.helm.model.HelmDependency;
import io.dekorate.helm.model.Maintainer;
import io.dekorate.helm.util.HelmValueHolder;
import io.dekorate.helm.util.ReadmeBuilder;
import io.dekorate.project.Project;
import io.dekorate.utils.Exec;
import io.dekorate.utils.Maps;
import io.dekorate.utils.Serialization;
import io.dekorate.utils.Strings;
import io.github.yamlpath.YamlExpressionParser;
import io.github.yamlpath.YamlPath;

public class HelmWriterSessionListener implements SessionListener, WithProject, WithSession {

  private static final String YAML = ".yaml";
  private static final String YAML_REG_EXP = ".*?\\.ya?ml$";
  private static final String CHART_FILENAME = "Chart" + YAML;
  private static final String VALUES = "values";
  private static final String TEMPLATES = "templates";
  private static final String CHARTS = "charts";
  private static final String NOTES = "NOTES.txt";
  private static final String VALUES_SCHEMA = "values.schema.json";
  private static final String README = "README.md";
  private static final List<String> ADDITIONAL_CHART_FILES = Arrays.asList("LICENSE", "app-readme.md",
      "questions.yml", "questions.yaml", "requirements.yml", "requirements.yaml", "crds");
  private static final String KIND = "kind";
  private static final String METADATA = "metadata";
  private static final String NAME = "name";
  private static final String START_TAG = "{{";
  private static final String END_TAG = "}}";
  private static final String VALUES_START_TAG = START_TAG + " .Values.";
  private static final String VALUES_END_TAG = " " + END_TAG;
  private static final String EMPTY = "";
  private static final String IF_STATEMENT_START_TAG = "{{- if .Values.%s }}";
  private static final String TEMPLATE_FUNCTION_START_TAG = "{{- define";
  private static final String TEMPLATE_FUNCTION_END_TAG = "{{- end }}";
  private static final String HELM_HELPER_PREFIX = "_";
  private static final boolean APPEND = true;
  private static final String SEPARATOR_TOKEN = ":LINE_SEPARATOR:";
  private static final String SEPARATOR_QUOTES = ":DOUBLE_QUOTES";
  private static final String START_EXPRESSION_TOKEN = ":START:";
  private static final String END_EXPRESSION_TOKEN = ":END:";
  private static final Logger LOGGER = LoggerFactory.getLogger();

  /**
   * Invoked when the session is closed
   */
  @Override
  public void onClosed() {
    Session session = getSession();
    Project project = getProject();
    Path outputDir = project.getBuildInfo().getClassOutputDir().resolve(project.getDekorateOutputDir());
    session.getConfigurationRegistry().get(HelmChartConfig.class).ifPresent(helmConfig -> {
      Path baseDir = getProject().getBuildInfo().getResourceDir();
      if (getProject().getDekorateInputDir() != null) {
        baseDir = baseDir.resolve(getProject().getDekorateInputDir());
      }
      Path inputDir = baseDir.resolve(helmConfig.getInputFolder());

      List<ConfigReference> configReferences = Stream.of(helmConfig.getValues())
          .map(this::toConfigReference)
          .collect(Collectors.toList());

      writeHelmFiles(session, project, helmConfig, configReferences, inputDir, outputDir.resolve(helmConfig.getOutputFolder()),
          listYamls(outputDir));
    });
  }

  /**
   * Needs to be public in order to be called from outside the session context.
   * 
   * @return the list of the Helm generated files.
   */
  public Map<String, String> writeHelmFiles(Session session, Project project,
      HelmChartConfig helmConfig, List<ConfigReference> configReferences,
      Path inputDir,
      Path outputDir,
      Collection<File> generatedFiles) {
    Map<String, String> artifacts = new HashMap<>();
    if (helmConfig.isEnabled()) {
      validateHelmConfig(helmConfig);
      List<ConfigReference> valuesReferences = mergeValuesReferencesFromDecorators(helmConfig, configReferences, session);

      try {
        LOGGER.info(String.format("Creating Helm Chart \"%s\"", helmConfig.getName()));
        Map<String, HelmValueHolder> prodValues = new HashMap<>();
        Map<String, Map<String, HelmValueHolder>> valuesByProfile = new HashMap<>();
        artifacts.putAll(processTemplates(helmConfig, inputDir, outputDir, generatedFiles, valuesReferences, prodValues,
            valuesByProfile));
        artifacts.putAll(createChartYaml(helmConfig, project, inputDir, outputDir));
        artifacts.putAll(createValuesYaml(helmConfig, valuesReferences, inputDir, outputDir, prodValues, valuesByProfile));

        // To follow Helm file structure standards:
        artifacts.putAll(createEmptyChartFolder(helmConfig, outputDir));
        artifacts.putAll(addNotesIntoTemplatesFolder(helmConfig, inputDir, outputDir));
        artifacts.putAll(addAdditionalResources(helmConfig, inputDir, outputDir));

        // Final step: packaging
        if (helmConfig.isCreateTarFile()) {
          fetchDependencies(helmConfig, outputDir);
          artifacts.putAll(createTarball(helmConfig, project, outputDir, artifacts));
        }

      } catch (IOException e) {
        throw new RuntimeException("Error writing resources", e);
      }
    }

    return artifacts;
  }

  private Map<String, String> addAdditionalResources(HelmChartConfig helmConfig, Path inputDir, Path outputDir)
      throws IOException {
    if (inputDir == null || !inputDir.toFile().exists()) {
      return Collections.emptyMap();
    }

    Map<String, String> artifacts = new HashMap<>();
    for (File source : inputDir.toFile().listFiles()) {
      if (ADDITIONAL_CHART_FILES.stream().anyMatch(source.getName()::equalsIgnoreCase)) {
        artifacts.putAll(addAdditionalResource(helmConfig, outputDir, source));
      }
    }

    return artifacts;
  }

  private Map<String, String> addAdditionalResource(HelmChartConfig helmConfig, Path outputDir, File source)
      throws IOException {
    if (!source.exists()) {
      return Collections.emptyMap();
    }

    Path destination = getChartOutputDir(helmConfig, outputDir).resolve(source.getName());
    if (source.isDirectory()) {
      Files.createDirectory(destination);
      for (File file : source.listFiles()) {
        Files.copy(new FileInputStream(file), destination.resolve(file.getName()));
      }
    } else {
      Files.copy(new FileInputStream(source), destination);
    }

    return Collections.singletonMap(destination.toString(), EMPTY);
  }

  private void fetchDependencies(HelmChartConfig helmConfig, Path outputDir) {
    if (helmConfig.getDependencies() != null && helmConfig.getDependencies().length > 0) {
      Path chartFolder = getChartOutputDir(helmConfig, outputDir);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      boolean success = Exec.inPath(chartFolder)
          .redirectingOutput(out)
          .commands("helm", "dependency", "build");

      if (success) {
        LOGGER.info("Dependencies successfully fetched");
      } else {
        throw new RuntimeException("Error fetching Helm dependencies. Cause: " + new String(out.toByteArray()));
      }
    }
  }

  private void validateHelmConfig(HelmChartConfig helmConfig) {
    if (Strings.isNullOrEmpty(helmConfig.getName())) {
      throw new RuntimeException("Helm Chart name is required!");
    }
  }

  private Map<String, String> addNotesIntoTemplatesFolder(HelmChartConfig helmConfig, Path inputDir, Path outputDir)
      throws IOException {
    InputStream notesInputStream;

    File notesInInputDir = inputDir.resolve(NOTES).toFile();
    if (notesInInputDir.exists()) {
      notesInputStream = new FileInputStream(notesInInputDir);
    } else {
      if (Strings.isNullOrEmpty(helmConfig.getNotes())) {
        return Collections.emptyMap();
      }

      notesInputStream = getResourceFromClasspath(helmConfig.getNotes());
    }

    if (notesInputStream == null) {
      throw new RuntimeException("Could not find the notes template file in the classpath at " + helmConfig.getNotes());
    }
    Path chartOutputDir = getChartOutputDir(helmConfig, outputDir).resolve(TEMPLATES).resolve(NOTES);
    Files.copy(notesInputStream, chartOutputDir);
    return Collections.singletonMap(chartOutputDir.toString(), EMPTY);
  }

  private InputStream getResourceFromClasspath(String notes) {
    // Try to locate the file from the context class loader
    InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(notes);
    if (is == null) {
      // if not found, try to find it in the current classpath.
      is = HelmWriterSessionListener.class.getResourceAsStream(notes);
    }

    return is;
  }

  private Map<String, String> createEmptyChartFolder(HelmChartConfig helmConfig, Path outputDir) throws IOException {
    Path emptyChartsDir = getChartOutputDir(helmConfig, outputDir).resolve(CHARTS);
    Files.createDirectories(emptyChartsDir);
    return Collections.singletonMap(emptyChartsDir.toString(), EMPTY);
  }

  private List<ConfigReference> mergeValuesReferencesFromDecorators(HelmChartConfig helmConfig,
      List<ConfigReference> configReferencesFromConfig, Session session) {
    List<ConfigReference> configReferences = new LinkedList<>();
    // From user
    configReferences.addAll(configReferencesFromConfig);
    // From if statements: these are boolean values
    for (AddIfStatement addIfStatement : helmConfig.getAddIfStatements()) {
      configReferences.add(new ConfigReference.Builder(deductProperty(helmConfig, addIfStatement.getProperty()), new String[0])
          .withDescription(addIfStatement.getDescription())
          .withValue(addIfStatement.getWithDefaultValue())
          .build());
    }
    // From decorators: We need to reverse the order as the latest decorator was the latest applied and hence the one
    // we should use.
    List<ConfigReference> configReferencesFromDecorators = session.getResourceRegistry().getConfigReferences()
        .stream()
        .flatMap(decorator -> decorator.getConfigReferences().stream())
        .collect(Collectors.toList());

    Collections.reverse(configReferencesFromDecorators);
    configReferences.addAll(configReferencesFromDecorators);

    return configReferences;
  }

  private boolean valueHasPath(ConfigReference valueReference) {
    return valueReference.getPaths() != null && valueReference.getPaths().length > 0;
  }

  private ConfigReference toConfigReference(ValueReference valueReference) {
    return new ConfigReference.Builder(valueReference.getProperty(), valueReference.getPaths())
        .withValue(Strings.isNullOrEmpty(valueReference.getValue()) ? null : valueReference.getValue())
        .withDescription(valueReference.getDescription())
        .withExpression(valueReference.getExpression())
        .withProfile(valueReference.getProfile())
        .withMinimum(valueReference.getMinimum())
        .withMaximum(valueReference.getMaximum())
        .withPattern(valueReference.getPattern())
        .withRequired(valueReference.isRequired())
        .build();
  }

  private Map<String, String> createValuesYaml(HelmChartConfig helmConfig, List<ConfigReference> configReferences,
      Path inputDir, Path outputDir,
      Map<String, HelmValueHolder> prodValues,
      Map<String, Map<String, HelmValueHolder>> valuesByProfile)
      throws IOException {

    // Populate user prod values without expression from properties
    for (ConfigReference value : configReferences) {
      if (!valueHasPath(value)) {
        if (value.getValue() == null) {
          throw new RuntimeException("The value mapping for " + value.getProperty() + " does not have "
              + "either a path or a default value. ");
        }

        getValues(prodValues, valuesByProfile, value)
            .put(deductProperty(helmConfig, value.getProperty()), new HelmValueHolder(value.getValue(), value));
      }
    }

    Map<String, String> artifacts = new HashMap<>();

    // first, we process the values in each profile
    for (Map.Entry<String, Map<String, HelmValueHolder>> valuesInProfile : valuesByProfile.entrySet()) {
      String profile = valuesInProfile.getKey();
      Map<String, HelmValueHolder> values = valuesInProfile.getValue();
      // Populate the profiled values with the one from prod if the key does not exist
      for (Map.Entry<String, HelmValueHolder> prodValue : prodValues.entrySet()) {
        if (!values.containsKey(prodValue.getKey())) {
          values.put(prodValue.getKey(), prodValue.getValue());
        }
      }

      // Create the values.<profile>.yaml file
      artifacts.putAll(writeFileAsYaml(mergeWithFileIfExists(inputDir, VALUES + YAML, toValuesMap(values)),
          getChartOutputDir(helmConfig, outputDir).resolve(VALUES + "." + profile + YAML)));
    }

    // Next, we process the prod profile
    artifacts.putAll(writeFileAsYaml(mergeWithFileIfExists(inputDir, VALUES + YAML, toValuesMap(prodValues)),
        getChartOutputDir(helmConfig, outputDir).resolve(VALUES + YAML)));

    // Next, the "values.schema.json" file
    if (helmConfig.isCreateValuesSchemaFile()) {
      Map<String, Object> schemaAsMap = createSchema(helmConfig, prodValues);
      artifacts.putAll(writeFileAsJson(mergeWithFileIfExists(inputDir, VALUES_SCHEMA, toMultiValueSortedMap(schemaAsMap)),
          getChartOutputDir(helmConfig, outputDir).resolve(VALUES_SCHEMA)));
    } else {
      artifacts.putAll(addAdditionalResource(helmConfig, outputDir, inputDir.resolve(VALUES_SCHEMA).toFile()));
    }

    // Next, the "README.md" file
    if (helmConfig.isCreateReadmeFile()) {
      String readmeContent = ReadmeBuilder.build(helmConfig, prodValues);
      artifacts.putAll(writeFile(readmeContent, getChartOutputDir(helmConfig, outputDir).resolve(README)));
    } else {
      artifacts.putAll(addAdditionalResource(helmConfig, outputDir, inputDir.resolve(README).toFile()));
    }

    return artifacts;
  }

  private Map<String, Object> toValuesMap(Map<String, HelmValueHolder> holder) {
    Map<String, Object> values = new HashMap<>();
    for (Map.Entry<String, HelmValueHolder> value : holder.entrySet()) {
      values.put(value.getKey(), value.getValue().value);
    }

    return toMultiValueSortedMap(values);
  }

  private Map<String, Object> mergeWithFileIfExists(Path inputDir, String file, Map<String, Object> values) {
    File templateValuesFile = inputDir.resolve(file).toFile();
    if (templateValuesFile.exists()) {
      Map<String, Object> result = new HashMap<>();
      Map<String, Object> yaml = Serialization.unmarshal(templateValuesFile,
          new TypeReference<Map<String, Object>>() {
          });
      result.putAll(yaml);
      // first, incorporate the properties from the file
      Maps.merge(values, result);
      // then, merge it with the generated data
      Maps.merge(result, values);
      return result;
    }

    return values;
  }

  private Map<String, String> createTarball(HelmChartConfig helmConfig, Project project, Path outputDir,
      Map<String, String> artifacts) throws IOException {

    File tarballFile = outputDir.resolve(String.format("%s-%s%s.%s",
        helmConfig.getName(),
        getVersion(helmConfig, project),
        Strings.isNullOrEmpty(helmConfig.getTarFileClassifier()) ? EMPTY : "-" + helmConfig.getTarFileClassifier(),
        helmConfig.getExtension()))
        .toFile();

    LOGGER.debug(String.format("Creating Helm configuration Tarball: '%s'", tarballFile));

    Path helmSources = getChartOutputDir(helmConfig, outputDir);

    List<File> files = new ArrayList<>();
    for (String filePath : artifacts.keySet()) {
      File file = new File(filePath);
      if (file.isDirectory()) {
        files.addAll(Arrays.asList(file.listFiles()));
      } else {
        files.add(file);
      }
    }

    createTarBall(tarballFile, helmSources.toFile(), files, helmConfig.getExtension(),
        tae -> tae.setName(String.format("%s/%s", helmConfig.getName(), tae.getName())));

    return Collections.singletonMap(tarballFile.toString(), null);
  }

  private String getVersion(HelmChartConfig helmConfig, Project project) {
    if (Strings.isNullOrEmpty(helmConfig.getVersion())) {
      return project.getBuildInfo().getVersion();
    }

    return helmConfig.getVersion();
  }

  private Map<String, String> processTemplates(HelmChartConfig helmConfig, Path inputDir, Path outputDir,
      Collection<File> generatedFiles, List<ConfigReference> valuesReferences, Map<String, HelmValueHolder> prodValues,
      Map<String, Map<String, HelmValueHolder>> valuesByProfile) throws IOException {

    Map<String, String> templates = new HashMap<>();
    Path templatesDir = getChartOutputDir(helmConfig, outputDir).resolve(TEMPLATES);
    Files.createDirectories(templatesDir);
    List<Map<Object, Object>> resources = replaceValuesInYamls(helmConfig, generatedFiles, valuesReferences, prodValues,
        valuesByProfile);
    Map<String, String> functionsByResource = processUserDefinedTemplates(inputDir, templates, templatesDir);
    // Split yamls in separated files by kind
    for (Map<Object, Object> resource : resources) {
      // Add user defined expressions
      if (helmConfig.getExpressions() != null) {
        YamlExpressionParser parser = new YamlExpressionParser(Arrays.asList(resource));
        for (HelmExpression expressionConfig : helmConfig.getExpressions()) {
          if (expressionConfig.getPath() != null && expressionConfig.getExpression() != null) {
            readAndSet(parser, expressionConfig.getPath(), expressionConfig.getExpression());
          }
        }
      }

      String kind = (String) resource.get(KIND);
      Path targetFile = templatesDir.resolve(kind.toLowerCase() + YAML);
      String functions = functionsByResource.get(kind.toLowerCase() + YAML);

      // Adapt the values tag to Helm standards:
      String adaptedString = Serialization.yamlMapper().writeValueAsString(resource);
      if (functions != null) {
        adaptedString = functions + System.lineSeparator() + adaptedString;
      }

      // Add if statements at resource level
      for (AddIfStatement addIfStatement : helmConfig.getAddIfStatements()) {
        if ((Strings.isNullOrEmpty(addIfStatement.getOnResourceKind())
            || Strings.equals(addIfStatement.getOnResourceKind(), kind))
            && (Strings.isNullOrEmpty(addIfStatement.getOnResourceName())
                || Strings.equals(addIfStatement.getOnResourceName(), getNameFromResource(resource)))) {

          adaptedString = String.format(IF_STATEMENT_START_TAG, deductProperty(helmConfig, addIfStatement.getProperty()))
              + System.lineSeparator()
              + adaptedString
              + System.lineSeparator()
              + TEMPLATE_FUNCTION_END_TAG
              + System.lineSeparator();
        }
      }

      adaptedString = adaptedString
          .replaceAll(Pattern.quote("\"" + START_TAG), START_TAG)
          .replaceAll(Pattern.quote(END_TAG + "\""), END_TAG)
          .replaceAll("\"" + START_EXPRESSION_TOKEN, EMPTY)
          .replaceAll(END_EXPRESSION_TOKEN + "\"", EMPTY)
          .replaceAll(SEPARATOR_QUOTES, "\"")
          .replaceAll(SEPARATOR_TOKEN, System.lineSeparator())
          // replace randomly escape characters that is entered by Jackson readTree method:
          .replaceAll("\\\\\\n(\\s)*\\\\", EMPTY);

      writeFile(adaptedString, targetFile);
      templates.put(targetFile.toString(), adaptedString);
    }

    return templates;
  }

  private String getNameFromResource(Map<Object, Object> resource) {
    Object metadata = resource.get(METADATA);
    if (metadata != null && metadata instanceof Map) {
      Object name = ((Map) metadata).get(NAME);
      if (name != null) {
        return name.toString();
      }
    }

    return null;
  }

  private Map<String, String> processUserDefinedTemplates(Path inputDir, Map<String, String> templates, Path templatesDir)
      throws IOException {
    Map<String, String> functionsByResource = new HashMap<>();

    File inputTemplates = inputDir.resolve(TEMPLATES).toFile();
    if (inputTemplates.exists()) {
      File[] userTemplates = inputTemplates.listFiles();
      if (userTemplates != null) {
        for (File userTemplateFile : userTemplates) {
          if (userTemplateFile.getName().startsWith(HELM_HELPER_PREFIX)) {
            // it's a helper Helm file, include as it is
            Path output = templatesDir.resolve(userTemplateFile.getName());
            Files.copy(new FileInputStream(userTemplateFile), output);
            templates.put(output.toString(), EMPTY);
          } else {
            // it's a resource template, let's extract only the template functions and include
            // it into the generated file later.
            String[] userResource = Strings.read(new FileInputStream(userTemplateFile)).split(System.lineSeparator());

            StringBuilder sb = new StringBuilder();
            boolean isFunction = false;
            for (String lineUserResource : userResource) {
              if (lineUserResource.contains(TEMPLATE_FUNCTION_START_TAG) || isFunction) {
                isFunction = !lineUserResource.contains(TEMPLATE_FUNCTION_END_TAG);
                sb.append(lineUserResource + System.lineSeparator());
              }
            }

            functionsByResource.put(userTemplateFile.getName(), sb.toString());
          }
        }
      }
    }
    return functionsByResource;
  }

  private List<Map<Object, Object>> replaceValuesInYamls(HelmChartConfig helmConfig,
      Collection<File> generatedFiles,
      List<ConfigReference> valuesReferences,
      Map<String, HelmValueHolder> prodValues,
      Map<String, Map<String, HelmValueHolder>> valuesByProfile) throws IOException {

    List<Map<Object, Object>> allResources = new LinkedList<>();
    for (File generatedFile : generatedFiles) {
      if (!generatedFile.getName().toLowerCase().matches(YAML_REG_EXP)) {
        continue;
      }

      // Read helm expression parsers
      YamlExpressionParser parser = YamlPath.from(new FileInputStream(generatedFile));
      // Seen lookup by default values.yaml file.
      Map<String, Object> seen = new HashMap<>();

      for (ConfigReference valueReference : valuesReferences) {
        String valueReferenceProperty = deductProperty(helmConfig, valueReference.getProperty());

        if (seen.containsKey(valueReferenceProperty)) {
          if (Strings.isNotNullOrEmpty(valueReference.getProfile())) {
            Object value = Optional.ofNullable(valueReference.getValue())
                .orElse(seen.get(valueReferenceProperty));
            getValues(prodValues, valuesByProfile, valueReference).put(valueReferenceProperty,
                new HelmValueHolder(value, valueReference));
          }

          continue;
        }

        // Check whether path exists
        for (String path : valueReference.getPaths()) {
          String expression = Optional.ofNullable(valueReference.getExpression())
              .filter(Strings::isNotNullOrEmpty)
              .orElse(VALUES_START_TAG + valueReferenceProperty + VALUES_END_TAG);

          Object found = readAndSet(parser, path, expression);

          Object value = Optional.ofNullable(valueReference.getValue()).orElse(found);
          if (value != null) {
            seen.put(valueReferenceProperty, value);
            getValues(prodValues, valuesByProfile, valueReference).put(valueReferenceProperty,
                new HelmValueHolder(value, valueReference));
          }
        }
      }

      allResources.addAll(parser.getResources());
    }

    return allResources;
  }

  private Map<String, HelmValueHolder> getValues(Map<String, HelmValueHolder> prodValues,
      Map<String, Map<String, HelmValueHolder>> valuesByProfile,
      ConfigReference valueReference) {
    String valueProfile = valueReference.getProfile();
    Map<String, HelmValueHolder> values = prodValues;
    if (Strings.isNotNullOrEmpty(valueProfile)) {
      values = valuesByProfile.get(valueProfile);
      if (values == null) {
        values = new HashMap<>();
        valuesByProfile.put(valueProfile, values);
      }
    }

    return values;
  }

  private Map<String, String> createChartYaml(HelmChartConfig helmConfig, Project project, Path inputDir, Path outputDir)
      throws IOException {
    final Chart chart = new Chart();
    chart.setName(helmConfig.getName());
    chart.setVersion(getVersion(helmConfig, project));
    chart.setDescription(helmConfig.getDescription());
    chart.setHome(helmConfig.getHome());
    chart.setSources(Arrays.asList(helmConfig.getSources()));
    chart.setMaintainers(Arrays.stream(helmConfig.getMaintainers())
        .map(m -> new Maintainer(m.getName(), m.getEmail(), m.getUrl()))
        .collect(Collectors.toList()));
    chart.setIcon(helmConfig.getIcon());
    chart.setApiVersion(helmConfig.getApiVersion());
    chart.setCondition(helmConfig.getCondition());
    chart.setTags(helmConfig.getTags());
    chart.setAppVersion(helmConfig.getAppVersion());
    if (helmConfig.isDeprecated()) {
      chart.setDeprecated(helmConfig.isDeprecated());
    }
    chart.setAnnotations(Arrays.stream(helmConfig.getAnnotations())
        .collect(Collectors.toMap(Annotation::getKey, Annotation::getValue)));
    chart.setKubeVersion(helmConfig.getKubeVersion());
    chart.setKeywords(Arrays.asList(helmConfig.getKeywords()));
    chart.setDependencies(Arrays.stream(helmConfig.getDependencies())
        .map(d -> new HelmDependency(d.getName(),
            Strings.defaultIfEmpty(d.getAlias(), d.getName()),
            d.getVersion(),
            d.getRepository(),
            d.getCondition(),
            d.getTags(),
            d.isEnabled()))
        .collect(Collectors.toList()));
    chart.setType(helmConfig.getType());

    Path yml = getChartOutputDir(helmConfig, outputDir).resolve(CHART_FILENAME).normalize();
    File userChartFile = inputDir.resolve(CHART_FILENAME).toFile();
    Object chartContent = chart;
    if (userChartFile.exists()) {
      chartContent = mergeWithFileIfExists(inputDir, CHART_FILENAME,
          toMultiValueUnsortedMap(Serialization.yamlMapper().readValue(Serialization.asYaml(chart), Map.class)));
    }

    return writeFileAsYaml(chartContent, yml);
  }

  private Map<String, String> writeFileAsYaml(Object data, Path file) throws IOException {
    String value = Serialization.asYaml(data);
    return writeFile(value, file);
  }

  private Map<String, String> writeFileAsJson(Object data, Path file) throws IOException {
    String value = Serialization.asJson(data);
    return writeFile(value, file);
  }

  private Map<String, String> writeFile(String value, Path file) throws IOException {
    try (FileWriter writer = new FileWriter(file.toFile(), APPEND)) {
      writer.write(value);
      return Collections.singletonMap(file.toString(), value);
    }
  }

  private Path getChartOutputDir(HelmChartConfig helmConfig, Path outputDir) {
    return outputDir.resolve(helmConfig.getName());
  }

  private static List<File> listYamls(Path directory) {
    return Stream.of(Optional.ofNullable(directory.toFile().listFiles()).orElse(new File[0]))
        .filter(File::isFile)
        .filter(f -> f.getName().toLowerCase().matches(YAML_REG_EXP))
        .collect(Collectors.toList());
  }

  private static Object readAndSet(YamlExpressionParser parser, String path, String expression) {
    Set<Object> found = parser.readAndReplace(path, START_EXPRESSION_TOKEN +
        expression.replaceAll(Pattern.quote(System.lineSeparator()), SEPARATOR_TOKEN)
            .replaceAll(Pattern.quote("\""), SEPARATOR_QUOTES)
        + END_EXPRESSION_TOKEN);
    return found.stream().findFirst().orElse(null);
  }

}
