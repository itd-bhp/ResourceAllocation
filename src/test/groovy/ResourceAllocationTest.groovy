
import org.aspectj.lang.annotation.Before
import org.example.ResourceAllocation
import org.junit.jupiter.api.Test

import java.text.SimpleDateFormat

import static org.junit.jupiter.api.Assertions.*

class ResourceAllocationTest {

    def resourceAllocation = new ResourceAllocation()  // Assuming getNumberOfPeriods() is in this class

    @Test
    void testGetNumberOfPeriods_Monthly() {
        Date startDate = new SimpleDateFormat("yyyy-MM-dd").parse("2023-01-01")
        Date endDate = new SimpleDateFormat("yyyy-MM-dd").parse("2024-01-01")
        Integer periods = resourceAllocation.getNumberOfPeriods(startDate, endDate, "MONTHLY")
        assertEquals(12, periods)
    }

    @Test
    void testGetNumberOfPeriods_Quarterly() {
        Date startDate = new SimpleDateFormat("yyyy-MM-dd").parse("2023-01-01")
        Date endDate = new SimpleDateFormat("yyyy-MM-dd").parse("2024-01-01")
        Integer periods = resourceAllocation.getNumberOfPeriods(startDate, endDate, "QUARTERLY")
        assertEquals(4, periods)
    }

    @Test
    void testGetNumberOfPeriods_Yearly() {
        Date startDate = new SimpleDateFormat("yyyy-MM-dd").parse("2023-01-01")
        Date endDate = new SimpleDateFormat("yyyy-MM-dd").parse("2025-01-01")
        Integer periods = resourceAllocation.getNumberOfPeriods(startDate, endDate, "YEARLY")
        assertEquals(2, periods)
    }

    @Test
    void testGetNumberOfPeriods_Quarterly_BasedOnMonths() {
        Date startDate = new SimpleDateFormat("yyyy-MM-dd").parse("2023-01-01")
        Date endDate = new SimpleDateFormat("yyyy-MM-dd").parse("2023-09-01")
        Integer periods = resourceAllocation.getNumberOfPeriods(startDate, endDate, "QUARTERLY")
        assertEquals(3, periods)
    }

    @Test
    void testGetNumberOfPeriods_InvalidPeriod() {
        Date startDate = new SimpleDateFormat("yyyy-MM-dd").parse("2023-01-01")
        Date endDate = new SimpleDateFormat("yyyy-MM-dd").parse("2024-01-01")

        try {
            resourceAllocation.getNumberOfPeriods(startDate, endDate, "INVALID_PERIOD")
            fail("Expected exception to be thrown for invalid period type")
        } catch (Exception e) {
            assertEquals("Invalid period type: INVALID_PERIOD", e.message)
        }
    }
    String outputFile = "test_output.csv"

    @Before
    void setup() {
        File file = new File(outputFile)
        if (file.exists()) {
            file.delete()
        }
    }

    @Test
    void testWriteToCSV_ValidData() {
        List<List<String>> data = [
                ["Project1", "Resource1", "01.Jan.2024", "31.Jan.2024", "50", "60"],
                ["Project2", "Resource2", "01.Feb.2024", "28.Feb.2024", "30", "40"]
        ]
        writeToCSV(data, outputFile)
        File file = new File(outputFile)
        assertTrue(file.exists())
        List<String> lines = Files.readAllLines(Paths.get(outputFile))
        assertEquals(3, lines.size()) // Expecting 3 lines: header + 2 data rows
        assertEquals("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)")
        assertEquals("Project1,Resource1,01.Jan.2024,31.Jan.2024,50,60")
        assertEquals("Project2,Resource2,01.Feb.2024,28.Feb.2024,30,40")
    }

    void writeToCSV(List<List<String>> data, String outputFile) {
        println "Writing data to CSV file: $outputFile"
        File file = new File(outputFile)
        file.withWriter { writer ->
            writer.writeLine("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)")
            data.each { row ->
                writer.writeLine(row.join(","))
            }
        }
        println "CSV file has been written successfully."
    }
}


