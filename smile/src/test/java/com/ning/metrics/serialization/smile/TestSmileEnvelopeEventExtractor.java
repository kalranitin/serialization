/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.metrics.serialization.smile;

import com.ning.metrics.serialization.event.SmileEnvelopeEvent;
import org.apache.log4j.Logger;
import org.codehaus.jackson.*;
import org.codehaus.jackson.smile.SmileFactory;
import org.codehaus.jackson.smile.SmileGenerator;
import org.codehaus.jackson.smile.SmileParser;
import org.joda.time.DateTime;

import java.io.*;
import java.util.HashMap;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

/*
    Tests SmileEnvelopeEventSerializer, SmileEnvelopeEventExtractor, & SmileEnvelopeEvent(JsonNode) constructor
 */
public class TestSmileEnvelopeEventExtractor
{
    private final static Logger log = Logger.getLogger(TestSmileEnvelopeEventExtractor.class);

    protected final static SmileFactory smileFactory = new SmileFactory();
    protected final static JsonFactory jsonFactory = new JsonFactory();

    static {
        // yes, full 'compression' by checking for repeating names, short string values:
        smileFactory.configure(SmileGenerator.Feature.CHECK_SHARED_NAMES, true);
        smileFactory.configure(SmileGenerator.Feature.CHECK_SHARED_STRING_VALUES, true);
        // and for now let's not mandate header for input
        smileFactory.configure(SmileParser.Feature.REQUIRE_HEADER, false);
    }

    @Test
    public void testJsonExtractAll() throws IOException
    {
        testExtractAll(true);
    }

    @Test
    public void testSmileExtractAll() throws IOException
    {
        testExtractAll(false);
    }

    @Test
    public void testJsonIncrementalExtract() throws IOException
    {
        testIncrementalExtract(true);
    }

    @Test
    public void testSmileIncrementalExtract() throws IOException
    {
        testIncrementalExtract(false);
    }

    private void testIncrementalExtract(boolean plainJson) throws IOException
    {
        final int numEvents = 5;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        SmileEnvelopeEvent event = makeSampleEvent();

        SmileEnvelopeEventSerializer serializer = new SmileEnvelopeEventSerializer(plainJson);
        serializer.open(out);
        for (int i = 0; i < numEvents; i++) {
            serializer.serialize(event);
        }
        serializer.close();

        InputStream in = new ByteArrayInputStream(out.toByteArray());
        String jsonString = out.toString();
        System.out.println(jsonString); // TODO

        SmileEnvelopeEventExtractor extractor = new SmileEnvelopeEventExtractor(in, plainJson);

        int numExtracted = 0;
        SmileEnvelopeEvent extractedEvent = extractor.extractNextEvent();
        while (extractedEvent != null) {
            numExtracted++;
            assertEventsMatch(extractedEvent, event);
            extractedEvent = extractor.extractNextEvent();
        }

        Assert.assertEquals(numExtracted, numEvents);
    }

    private void testExtractAll(boolean plainJson) throws IOException
    {
        final int numEvents = 5;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        SmileEnvelopeEvent event = makeSampleEvent();

        SmileEnvelopeEventSerializer serializer = new SmileEnvelopeEventSerializer(plainJson);
        serializer.open(out);
        for (int i = 0; i < numEvents; i++) {
            serializer.serialize(event);
        }
        serializer.close();

        InputStream in = new ByteArrayInputStream(out.toByteArray());
        List<SmileEnvelopeEvent> extractedEvents = SmileEnvelopeEventExtractor.extractEvents(in);

        Assert.assertEquals(extractedEvents.size(), numEvents);
        assertEventsMatch(extractedEvents.get(0), event);
    }

    private void assertEventsMatch(SmileEnvelopeEvent a, SmileEnvelopeEvent b)
    {
        Assert.assertEquals(a.getName(), b.getName());
        Assert.assertEquals(a.getGranularity(), b.getGranularity());
        Assert.assertEquals(a.getEventDateTime().getMillis(), b.getEventDateTime().getMillis());

        JsonNode aData = (JsonNode) a.getData();
        JsonNode bData = (JsonNode) b.getData();
        Assert.assertEquals(aData.get("firstName").getTextValue(), bData.get("firstName").getTextValue());
        Assert.assertEquals(aData.get("lastName").getTextValue(), bData.get("lastName").getTextValue());
        Assert.assertEquals(aData.get("theNumberFive").getIntValue(), bData.get("theNumberFive").getIntValue());
    }

    private SmileEnvelopeEvent makeSampleEvent() throws IOException
    {
        HashMap<String, Object> map = new HashMap<String, Object>();

        map.put("firstName", "joe");
        map.put("lastName", "sixPack");
        map.put("theNumberFive", 5);

        return new SmileEnvelopeEvent("sample", new DateTime(), map);
    }
}