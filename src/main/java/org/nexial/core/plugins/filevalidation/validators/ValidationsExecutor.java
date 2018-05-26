/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.plugins.filevalidation.validators;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.map.HashedMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.nexial.core.ExecutionThread;
import org.nexial.core.model.ExecutionContext;
import org.nexial.core.model.NexialFilterList;
import org.nexial.core.plugins.filevalidation.FieldBean;
import org.nexial.core.plugins.filevalidation.RecordBean;
import org.nexial.core.plugins.filevalidation.RecordData;
import org.nexial.core.plugins.filevalidation.config.FieldConfig;
import org.nexial.core.plugins.filevalidation.config.MapFunctionConfig;
import org.nexial.core.plugins.filevalidation.config.RecordConfig;
import org.nexial.core.plugins.filevalidation.config.ValidationConfig;
import org.nexial.core.plugins.filevalidation.validators.Error.ErrorBuilder;

import static java.math.RoundingMode.UP;

public class ValidationsExecutor {

    private static final Map<String, DataType> ALL_DATA_TYPES = new HashedMap<>();
    private FieldValidator startValidator;
    static final int DEC_SCALE = 25;
    static final RoundingMode ROUND = UP;
    private ExecutionContext context;

    public enum ValidationType {
        REGEX, EQUALS, SQL, API, DB, IN, DATE
    }

    public enum Severity {
        ERROR, WARNING
    }

    public enum DataType {
        // numeric, alphanumeric, alpha, any, blank, t/f
        NUMERIC("N", "Numeric", "Num", "Number"),
        ALPHANUMERIC("A/N", "Alphanumeric", "Alpha Numeric"),
        BLANK("Blank"),
        ANY("*", "any", " ");

        private Map<String, DataType> references = new HashMap<>();

        DataType(String... text) {
            Arrays.stream(text).forEach(key -> {
                ALL_DATA_TYPES.put(StringUtils.lowerCase(key), this);
                references.put(StringUtils.lowerCase(key), this);
            });
        }

        // before search for key, make sure you trim and lowercase
        public DataType toEnum(String text) {
            return ALL_DATA_TYPES.get(text);
        }

        public boolean isValidDataType(String text) { return ALL_DATA_TYPES.containsKey(text); }

        public boolean isNumeric(String text) {
            return NUMERIC.references.keySet().stream().anyMatch(text::equalsIgnoreCase);
        }

        public boolean isAlphaNumeric(String text) {
            return ALPHANUMERIC.references.keySet().stream().anyMatch(text::equalsIgnoreCase);
        }

        public boolean isBlank(String text) {
            return BLANK.references.keySet().stream().anyMatch(text::equalsIgnoreCase);
        }

        public boolean isAny(String text) {
            return ANY.references.keySet().stream().anyMatch(text::equalsIgnoreCase);
        }
    }

    public enum Alignment {
        L("L"),
        LEFT("Left"),
        R("R"),
        RIGHT("Right");
        private String alignmentType;

        Alignment(String alignmentType) {
            this.alignmentType = alignmentType;
        }

        public String toString() {
            return alignmentType;
        }
    }

    public ValidationsExecutor() {
        context = ExecutionThread.get();
        startValidator = new RegexValidator();
        startValidator.setNextValidator(new EqualsValidator()).setNextValidator(new InListValidator()).setNextValidator(
            new DateValidator()).setNextValidator(new SqlValidator());
    }

    public void executeValidations(RecordData recordData) {

        // TODO: refactor field validations to take the advantage of Nexial filter
        doFieldValidations(recordData);
        recordData.printMapFunctionValues();
        collectErrors(recordData);
    }

    public void doBasicValidations(RecordBean recordBean) {
        BasicValidator basicValidator = new BasicValidator();

        List<FieldBean> fields = recordBean.getFields();

        for (FieldBean field : fields) {

            basicValidator.validateField(field);
        }

    }

    public Map<String, Number> collectMapValues(RecordConfig recordConfig, RecordBean recordBean,
                                                Map<String, Number> mapValues) {

        List<MapFunctionConfig> mapFunctionConfigs = recordConfig.getMapFunctionConfigs();

        if (mapFunctionConfigs == null || mapFunctionConfigs.isEmpty()) { return mapValues; }

        updateValuesToContext(recordConfig, recordBean, mapValues);

        for (MapFunctionConfig mapFunctionConfig : mapFunctionConfigs) {

            List<FieldBean> recordFields = recordBean.getFields();

            String function = mapFunctionConfig.getFunction();
            FieldBean signField = recordBean.get(mapFunctionConfig.getSignField());
            String mapTo = mapFunctionConfig.getMapTo();

            for (FieldBean recordField : recordFields) {

                String fieldName = recordField.getConfig().getFieldname();

                if (!mapFunctionConfig.getFieldName().equals(fieldName)) {continue;}

                if (recordField.isDataTypeError()) {
                    context.logCurrentStep("skipped map function '" + function +
                                           "' due to validation error at field '" +
                                           fieldName + "'");
                    break;
                }

                String fieldValue = recordField.getFieldValue().trim();
                fieldValue = (signField != null) ? signField.getFieldValue() + fieldValue : fieldValue;
                BigDecimal big = NumberUtils.createBigDecimal((fieldValue));

                String condition = mapFunctionConfig.getCondition();
                if (condition != null && !isMatch(condition)) {
                    context.logCurrentStep("skipped map function for record number '" +
                                           recordBean.getRecordNumber() +
                                           "' due to failed condition '" +
                                           condition + "'");
                    continue;
                }

                if (function.equals("AVERAGE")) {
                    average(mapValues, mapTo, big);
                }

                if (function.equals("AGGREGATE")) {
                    aggregate(mapValues, mapTo, big);
                }

                if (function.equals("MIN")) {
                    min(mapValues, mapTo, big);
                }

                if (function.equals("MAX")) {
                    max(mapValues, mapTo, big);
                }

                if (function.equals("COUNT")) {
                    if (mapValues.containsKey(mapTo)) {
                        int counter = mapValues.get(mapTo).intValue();
                        mapValues.put(mapTo, ++counter);
                    } else { mapValues.put(mapTo, 1); }
                }
            }

        }

        cleanValuesFromContext(recordBean);
        return mapValues;
    }

    // todo: make all number functions as generic methods

    public void max(Map<String, Number> mapValues, String mapTo, BigDecimal big) {
        BigDecimal mapValue = mapValues.containsKey(mapTo) ?
                              big.max((BigDecimal) mapValues.get(mapTo)) :
                              big;
        mapValues.put(mapTo, mapValue);
    }

    public void min(Map<String, Number> mapValues, String mapTo, BigDecimal big) {
        BigDecimal mapValue = mapValues.containsKey(mapTo) ?
                              big.min((BigDecimal) mapValues.get(mapTo)) :
                              big;
        mapValues.put(mapTo, mapValue);
    }

    public void aggregate(Map<String, Number> mapValues, String mapTo, BigDecimal big) {
        BigDecimal mapValue = mapValues.containsKey(mapTo) ?
                              big.add((BigDecimal) mapValues.get(mapTo)) :
                              big;
        mapValues.put(mapTo, mapValue);
    }

    public void average(Map<String, Number> mapValues, String mapTo, BigDecimal big) {

        if (mapValues.containsKey(mapTo)) {
            BigDecimal counter = new BigDecimal(mapValues.get(mapTo + "#Counter").intValue() + 1);
            BigDecimal sum = big.add((BigDecimal) mapValues.get(mapTo + "#Sum"));
            mapValues.put(mapTo + "#Counter", counter);
            mapValues.put(mapTo + "#Sum", sum);
            mapValues.put(mapTo, sum.divide(counter, DEC_SCALE, ROUND));

        } else {
            mapValues.put(mapTo + "#Counter", 1);
            mapValues.put(mapTo + "#Sum", big);
            mapValues.put(mapTo, big);
        }
    }

    public void restoreValuesToContext(Map<String, Object> tempDupValues) {
        if (tempDupValues.isEmpty()) { return; }
        for (Entry<String, Object> stringObjectEntry : tempDupValues.entrySet()) {
            context.setData(stringObjectEntry.getKey(), stringObjectEntry.getValue());
            context.logCurrentStep("var '" +
                                   stringObjectEntry.getKey() +
                                   "' is restored to context with value '" +
                                   context.getObjectData(stringObjectEntry.getKey()) + "'");
        }
    }

    public void updateValuesToContext(RecordConfig recordConfig,
                                      RecordBean recordBean,
                                      Map<String, Number> mapValues) {
        List<MapFunctionConfig> mapFunctionConfigs = recordConfig.getMapFunctionConfigs();

        if (mapFunctionConfigs == null || mapFunctionConfigs.isEmpty()) { return; }

        for (MapFunctionConfig mapFunctionConfig : mapFunctionConfigs) {

            String mapTo = mapFunctionConfig.getMapTo();
            if (mapTo != null) {
                Number mapValue = (mapValues.get(mapTo) == null) ? 0 : mapValues.get(mapTo);
                context.setData(mapTo, mapValue);
            }
        }
        List<FieldBean> recordFields = recordBean.getFields();

        for (FieldBean recordField : recordFields) {
            String fName = recordField.getConfig().getFieldname();
            String fieldValue = recordField.getFieldValue();

            if (fieldValue == null) {
                context.removeData(fName);
            } else {
                context.setData(fName, truncateLeadingZeroes(fieldValue));
            }
        }


    }

    public Map<String, Object> moveDupValuesFromContext(List<RecordConfig> configs) {
        Map<String, Object> tempDupValues = new HashMap<>();

        for (RecordConfig config : configs) {
            if (config != null && CollectionUtils.isNotEmpty(config.getMapFunctionConfigs())) {
                for (MapFunctionConfig mapFunctionConfig : config.getMapFunctionConfigs()) {
                    String mapTo = mapFunctionConfig.getMapTo();
                    String fieldName = mapFunctionConfig.getFieldName();
                    if (mapTo != null) {
                        if (context.hasData(mapTo)) { tempDupValues.put(mapTo, context.getObjectData(mapTo)); }
                        if (context.hasData(fieldName)) {
                            tempDupValues.put(fieldName,
                                              context.getObjectData(fieldName));
                        }
                    }
                }
            }
        }
        return tempDupValues;
    }

    public void cleanValuesFromContext(RecordBean recordBean) {
        for (FieldBean fieldBean : recordBean.getFields()) {
            context.removeData(fieldBean.getConfig().getFieldname());
        }
    }

    public String truncateLeadingZeroes(String text) {
        // in case start with - or +
        boolean isNegative = StringUtils.startsWith(text, "-");
        text = StringUtils.removeStart(text, "+");
        text = StringUtils.removeStart(text, "-");

        text = StringUtils.removeFirst(text, "^0{1,}");
        if (StringUtils.isBlank(text)) {
            return null;
        }

        if (StringUtils.startsWithIgnoreCase(text, ".")) { text = "0" + text; }
        if (isNegative) { text = "-" + text; }
        return text;
    }

    public boolean isMatch(String condition) {
        NexialFilterList filters = new NexialFilterList(condition);
        return filters.isMatched(context, "filtering records with");
    }

    public static Error buildError(FieldBean field, Severity severity, String errorMessage, String validationType) {
        FieldConfig config = field.getConfig();

        return new ErrorBuilder().fieldName(config.getFieldname())
                                 .severity(severity.toString())
                                 .validationType(validationType)
                                 .errorMessage(errorMessage)
                                 .build();
    }

    private void collectErrors(RecordData recordData) {

        List<Error> errors = new ArrayList<>();

        for (Entry<Integer, RecordBean> recordEntry : recordData.getRecords().entrySet()) {

            List<FieldBean> recordFields = recordEntry.getValue().getFields();

            for (FieldBean recordField : recordFields) {

                if (recordField.getErrors() != null && recordField.getErrors().size() >= 1) {
                    for (Error error : recordField.getErrors()) {

                        error.setRecordLine(String.valueOf(recordEntry.getKey() + 1));
                        if (error.getSeverity().equals(Severity.ERROR.toString())) {
                            recordData.setHasError(true);
                        }

                        errors.add(error);
                    }
                }
            }
        }

        recordData.setErrors(errors);
    }

    private void doFieldValidations(RecordData recordData) {
        Map<Integer, RecordBean> records = recordData.getRecords();
        for (Entry<Integer, RecordBean> recordEntry : records.entrySet()) {
            List<FieldBean> fields = (recordEntry.getValue()).getFields();

            for (FieldBean field : fields) {
                List<ValidationConfig> validationConfigs = field.getConfig().getValidationConfigs();
                if (validationConfigs != null && !validationConfigs.isEmpty()) {
                    startValidator.validateField(field);
                }
            }

        }
    }


}
