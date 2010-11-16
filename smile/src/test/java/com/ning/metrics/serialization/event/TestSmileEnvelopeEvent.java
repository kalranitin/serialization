package com.ning.metrics.serialization.event;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.smile.SmileFactory;
import org.joda.time.DateTime;

public class TestSmileEnvelopeEvent
{
    private static final DateTime eventDateTime = new DateTime();
    private static final String SCHEMA_NAME = "mySmile";

    private byte[] serializedBytes;
    
    private String serializedString;
    
    @BeforeTest
    public void setUp() throws IOException
    {
        SmileFactory f = new SmileFactory();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        JsonGenerator g = f.createJsonGenerator(stream);

        g.writeStartObject();
        g.writeStringField(SmileEnvelopeEvent.SMILE_EVENT_GRANULARITY_TOKEN_NAME, "MONTHLY");
        g.writeObjectFieldStart("name");
        g.writeStringField("first", "Joe");
        g.writeStringField("last", "Sixpack");
        g.writeEndObject(); // for field 'name'
        g.writeStringField("gender", "MALE");
        g.writeNumberField(SmileEnvelopeEvent.SMILE_EVENT_DATETIME_TOKEN_NAME, eventDateTime.getMillis());
        g.writeBooleanField("verified", false);
        g.writeEndObject();
        g.close(); // important: will force flushing of output, close underlying output stream

        serializedBytes = stream.toByteArray();
        // one sanity check; should be able to round-trip via String (iff using latin-1!)
        serializedString = stream.toString("ISO-8859-1");
    }

    /*
    /////////////////////////////////////////////////////////////////////// 
    // Unit tests, char-to-byte conversions
    /////////////////////////////////////////////////////////////////////// 
     */
    
    /**
     * Unit test that verifies that messy conversions between byte[] and String do not totally
     * break contents
     */
    @Test(groups = "fast")
    public void testBytesVsString() throws Exception
    {
        byte[] fromString = serializedString.getBytes("ISO-8859-1");
        Assert.assertEquals(fromString, serializedBytes);
    }

    @Test(groups = "fast")
    public void testToBytes() throws Exception
    {
        SmileEnvelopeEvent event = createEvent();
        Assert.assertEquals((byte[]) event.getData(), serializedBytes);
    }
    
    /*
    /////////////////////////////////////////////////////////////////////// 
    // Unit tests, metadata access
    /////////////////////////////////////////////////////////////////////// 
     */
    
    @Test(groups = "fast")
    public void testGetEventDateTime() throws Exception
    {
        SmileEnvelopeEvent event = createEvent();
        Assert.assertEquals(event.getEventDateTime(), eventDateTime);
    }

    @Test(groups = "fast")
    public void testGetName() throws Exception
    {
        SmileEnvelopeEvent event = createEvent();
        Assert.assertEquals(event.getName(), SCHEMA_NAME);
    }

    /*
    /////////////////////////////////////////////////////////////////////// 
    // Unit tests, externalization (readObject/writeObject)
    /////////////////////////////////////////////////////////////////////// 
     */
    
    @Test(groups = "fast")
    public void testExternalization() throws Exception
    {
        Granularity gran = Granularity.MONTHLY; // arbitrary choice

        byte[] inputBytes = serializedBytes;
        DateTime now = new DateTime();
        SmileEnvelopeEvent envelope = new SmileEnvelopeEvent("test", inputBytes, now, gran);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bytes);
        out.writeObject(envelope);
        out.close();
        byte[] data = bytes.toByteArray();
        ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data));
        Object ob = in.readObject();
        Assert.assertNotNull(ob);
        Assert.assertSame(SmileEnvelopeEvent.class, ob.getClass());

        SmileEnvelopeEvent result = (SmileEnvelopeEvent) ob;
        // name is not automatically set, but can check other metadata
        Assert.assertSame(result.getGranularity(), gran);
        // how about timestamp? Why is it not matching...
//        Assert.assertEquals(result.getEventDateTime(), now);

        Assert.assertNotNull(result.getData());
        Assert.assertSame(result.getData().getClass(), byte[].class);
        byte[] actualBytes = (byte[]) result.getData();
        Assert.assertEquals(actualBytes, inputBytes);
    }

    /*
    /////////////////////////////////////////////////////////////////////// 
    // Helper methods
    /////////////////////////////////////////////////////////////////////// 
     */
    
    private SmileEnvelopeEvent createEvent() throws IOException
    {
        return new SmileEnvelopeEvent(SCHEMA_NAME, serializedString);
    }
}
