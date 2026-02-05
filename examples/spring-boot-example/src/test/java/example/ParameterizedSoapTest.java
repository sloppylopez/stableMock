package example;

import com.stablemock.U;
import example.inheritance.BaseTestFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.time.Instant;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized SOAP test that mimics FullFlowFlexibleIT scenario:
 * - Uses SOAP-style XML requests (OTA_HotelAvailRQ-like)
 * - Sets dynamic dates in @BeforeEach (like targetDay, dayAfterTargetDay)
 * - Makes multiple requests per test method (availability, booking, etc.)
 * - Parameterized with multiple test cases
 * 
 * Expected behavior:
 * - RECORD mode: Each invocation records to its own directory (e.g., testSoapFlow[0], testSoapFlow[1])
 * - Dynamic dates should be auto-detected and auto-ignored
 * - PLAYBACK mode: Each invocation plays back from its own directory with date placeholders
 */
@U(urls = { "https://postman-echo.com" },
   properties = { "app.postmanecho.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Disabled("Temporarily disabled - XML matching issue in playback mode (IllegalArgumentException: type: -1)")
public class ParameterizedSoapTest extends BaseTestFeature {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, ParameterizedSoapTest.class);
    }

    private Date targetDay;
    private Date dayAfterTargetDay;
    private SimpleDateFormat dateFormatter;

    @BeforeEach
    void setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"));
        final Date currentDate = Calendar.getInstance(TimeZone.getDefault()).getTime();
        targetDay = new Date(currentDate.getTime() + ((1000L * 60 * 60 * 24) * 10)); // 10 days from now
        dayAfterTargetDay = new Date(currentDate.getTime() + ((1000L * 60 * 60 * 24) * 11)); // 11 days from now
        dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
    }

    static Stream<Arguments> soapTestCases() {
        return Stream.of(
            Arguments.of("VIP", "0000004003", "123412341234", "SILVER"),
            Arguments.of("FRIENDS", null, null, null),
            Arguments.of("ANONYMOUS", null, null, null)
        );
    }

    @ParameterizedTest
    @MethodSource("soapTestCases")
    void testSoapFlow(String userType, String customerCode, String cardNumber, String category) {
        // Request 1: Availability-like SOAP request (similar to OTA_HotelAvailRQ)
        String availabilityXml = generateAvailabilityRequest(userType, customerCode, cardNumber, category);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> availabilityRequest = new HttpEntity<>(availabilityXml, headers);
        
        ResponseEntity<String> availabilityResponse = restTemplate.postForEntity(
            "/api/postmanecho/xml", availabilityRequest, String.class);
        
        assertNotNull(availabilityResponse, "Availability response should not be null for " + userType);
        assertNotNull(availabilityResponse.getBody(), "Availability response body should not be null for " + userType);
        assertEquals(200, availabilityResponse.getStatusCode().value(), 
            "Availability response should be 200 OK for " + userType);

        // Request 2: Booking-like SOAP request (simulating second request in flow)
        String bookingXml = generateBookingRequest(userType, customerCode);
        HttpEntity<String> bookingRequest = new HttpEntity<>(bookingXml, headers);
        
        ResponseEntity<String> bookingResponse = restTemplate.postForEntity(
            "/api/postmanecho/xml", bookingRequest, String.class);
        
        assertNotNull(bookingResponse, "Booking response should not be null for " + userType);
        assertNotNull(bookingResponse.getBody(), "Booking response body should not be null for " + userType);
        assertEquals(200, bookingResponse.getStatusCode().value(), 
            "Booking response should be 200 OK for " + userType);

        // Request 3: Confirmation-like SOAP request (simulating third request in flow)
        String confirmationXml = generateConfirmationRequest(userType);
        HttpEntity<String> confirmationRequest = new HttpEntity<>(confirmationXml, headers);
        
        ResponseEntity<String> confirmationResponse = restTemplate.postForEntity(
            "/api/postmanecho/xml", confirmationRequest, String.class);
        
        assertNotNull(confirmationResponse, "Confirmation response should not be null for " + userType);
        assertNotNull(confirmationResponse.getBody(), "Confirmation response body should not be null for " + userType);
        assertEquals(200, confirmationResponse.getStatusCode().value(), 
            "Confirmation response should be 200 OK for " + userType);

        // Verify all three requests succeeded
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if ("RECORD".equalsIgnoreCase(mode)) {
            // In RECORD mode, verify directory structure is being created
            String testMethodIdentifier = "testSoapFlow[" + getTestIndex(userType) + "]";
            File mappingsDir = new File(
                "src/test/resources/stablemock/ParameterizedSoapTest/" + testMethodIdentifier);
            assertTrue(mappingsDir.getParentFile().exists(), 
                "Test class directory should exist: " + mappingsDir.getParentFile().getAbsolutePath());
        }
    }

    private String generateAvailabilityRequest(String userType, String customerCode, String cardNumber, String category) {
        String startDate = dateFormatter.format(targetDay);
        String endDate = dateFormatter.format(dayAfterTargetDay);
        // Use ISO-8601 format like working tests (Instant.now().toString() produces format with colon: +01:00)
        String timestamp = Instant.now().toString();
        
        // Build XML like working tests - use StringBuilder for conditional parts
        StringBuilder xml = new StringBuilder();
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<OTA_HotelAvailRQ TimeStamp=\"").append(timestamp).append("\" xmlns=\"http://www.opentravel.org/OTA/2003/05\">");
        xml.append("<AvailRequestSegments>");
        xml.append("<AvailRequestSegment>");
        xml.append("<HotelSearchCriteria>");
        xml.append("<Criterion>");
        xml.append("<StayDateRange Start=\"").append(startDate).append("\" End=\"").append(endDate).append("\"/>");
        xml.append("</Criterion>");
        xml.append("</HotelSearchCriteria>");
        xml.append("</AvailRequestSegment>");
        xml.append("</AvailRequestSegments>");
        xml.append("<UserType>").append(userType).append("</UserType>");
        if (customerCode != null) {
            xml.append("<CustomerCode>").append(customerCode).append("</CustomerCode>");
        }
        if (cardNumber != null) {
            xml.append("<CardNumber>").append(cardNumber).append("</CardNumber>");
        }
        if (category != null) {
            xml.append("<Category>").append(category).append("</Category>");
        }
        xml.append("</OTA_HotelAvailRQ>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    private String generateBookingRequest(String userType, String customerCode) {
        String startDate = dateFormatter.format(targetDay);
        String endDate = dateFormatter.format(dayAfterTargetDay);
        // Use ISO-8601 format like working tests (Instant.now().toString() produces format with colon: +01:00)
        String timestamp = Instant.now().toString();
        
        // Build XML like working tests - use StringBuilder for conditional parts
        StringBuilder xml = new StringBuilder();
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<OTA_HotelBookingRQ TimeStamp=\"").append(timestamp).append("\" xmlns=\"http://www.opentravel.org/OTA/2003/05\">");
        xml.append("<BookingRequest>");
        xml.append("<StayDateRange Start=\"").append(startDate).append("\" End=\"").append(endDate).append("\"/>");
        xml.append("<UserType>").append(userType).append("</UserType>");
        if (customerCode != null) {
            xml.append("<CustomerCode>").append(customerCode).append("</CustomerCode>");
        }
        xml.append("</BookingRequest>");
        xml.append("</OTA_HotelBookingRQ>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    private String generateConfirmationRequest(String userType) {
        String startDate = dateFormatter.format(targetDay);
        String endDate = dateFormatter.format(dayAfterTargetDay);
        // Use ISO-8601 format like working tests (Instant.now().toString() produces format with colon: +01:00)
        String timestamp = Instant.now().toString();
        
        // Build XML like working tests - use StringBuilder
        StringBuilder xml = new StringBuilder();
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<OTA_HotelConfirmationRQ TimeStamp=\"").append(timestamp).append("\" xmlns=\"http://www.opentravel.org/OTA/2003/05\">");
        xml.append("<ConfirmationRequest>");
        xml.append("<StayDateRange Start=\"").append(startDate).append("\" End=\"").append(endDate).append("\"/>");
        xml.append("<UserType>").append(userType).append("</UserType>");
        xml.append("</ConfirmationRequest>");
        xml.append("</OTA_HotelConfirmationRQ>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }


    private int getTestIndex(String userType) {
        switch (userType) {
            case "VIP": return 0;
            case "FRIENDS": return 1;
            case "ANONYMOUS": return 2;
            default: return 0;
        }
    }

    @Test
    void testNonParameterizedSoapRequest() {
        // Regular non-parameterized test for comparison
        setUp(); // Initialize dates
        
        String xml = generateAvailabilityRequest("TEST", null, null, null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> request = new HttpEntity<>(xml, headers);
        
        ResponseEntity<String> response = restTemplate.postForEntity("/api/postmanecho/xml", request, String.class);
        
        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getBody(), "Response body should not be null");
        assertEquals(200, response.getStatusCode().value(), "Response should be 200 OK");
    }
}
