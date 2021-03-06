package io.zentity.model;

import org.junit.Test;

public class IndexFieldTest {

    public final static String VALID_OBJECT = "{\"attribute\":\"foo\",\"matcher\":\"bar\"}";

    ////  "indices".INDEX_NAME."fields"  ///////////////////////////////////////////////////////////////////////////////

    @Test
    public void testValid() throws Exception {
        new IndexField("index_name", "index_field_name", VALID_OBJECT);
    }

    @Test(expected = ValidationException.class)
    public void testInvalidUnexpectedField() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"matcher\":\"bar\",\"foo\":\"bar\"}");
    }

    ////  "indices".INDEX_NAME."fields".INDEX_FIELD_NAME  //////////////////////////////////////////////////////////////

    @Test(expected = ValidationException.class)
    public void testInvalidNameEmpty() throws Exception {
        new IndexField("index_name", " ", VALID_OBJECT);
    }

    ////  "indices".INDEX_NAME."fields".INDEX_FIELD_NAME."attribute"  //////////////////////////////////////////////////

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeMissing() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"matcher\":\"bar\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeEmpty() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\" \",\"matcher\":\"bar\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeTypeArray() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":[],\"matcher\":\"bar\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeTypeBoolean() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":true,\"matcher\":\"bar\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeTypeFloat() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":1.0,\"matcher\":\"bar\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeTypeInteger() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":1,\"matcher\":\"bar\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeTypeNull() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":null,\"matcher\":\"bar\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidAttributeTypeObject() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":{},\"matcher\":\"bar\"}");
    }

    ////  "indices".INDEX_NAME."fields".INDEX_FIELD_NAME."matcher"  ////////////////////////////////////////////////////

    @Test
    public void testValidMatcherMissing() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidMatcherEmpty() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"matcher\":\" \"}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidMatcherTypeArray() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"matcher\":[]}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidMatcherTypeBoolean() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"matcher\":true}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidMatcherTypeFloat() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"matcher\":1.0}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidMatcherTypeInteger() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"matcher\":1}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidMatcherTypeObject() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"matcher\":{}}");
    }

    ////  "indices".INDEX_NAME."fields".INDEX_FIELD_NAME."quality"  ////////////////////////////////////////////////////

    @Test
    public void testValidQualityValue() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":0.0}");
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":0.5}");
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":1.0}");
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":null}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidQualityTypeArray() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":[]}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidQualityTypeBoolean() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":true}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidQualityTypeInteger() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":1}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidQualityTypeFloatNegative() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":-1.0}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidQualityTypeObject() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":{}}");
    }

    @Test(expected = ValidationException.class)
    public void testInvalidQualityValueTooHigh() throws Exception {
        new IndexField("index_name", "index_field_name", "{\"attribute\":\"foo\",\"quality\":100.0}");
    }
}
