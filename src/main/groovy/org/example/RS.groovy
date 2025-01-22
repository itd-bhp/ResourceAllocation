import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import com.niku.xmlserver.core.NkTime
import com.niku.xmlserver.blob.NkCalendar
import groovy.sql.Sql
import groovy.time.TimeCategory
import java.sql.DriverManager
import java.sql.Connection
import java.sql.Blob

// Database connection details
def url = "jdbc:oracle:thin:@//10.0.0.35:11521/clarity" // Replace with your DB details
def user = "niku"
def password = "niku"

// Establish the database connection
Connection connection = DriverManager.getConnection(url, user, password)
def sql = new Sql(connection)

// Parameters for the AllocationMigrationJob
def project = "PR1016"
def period = "monthly" // Options: "daily", "weekly", "monthly, "quarterly"

// Method to fetch fromDate and toDate from the inv_investments table
Map<String, Date> getProjectDates(Sql sql, String project) {
    def query = """
        SELECT schedule_start, schedule_finish
        FROM inv_investments
        WHERE code = ?
    """
    def result = sql.firstRow(query, [project])

    if (result) {
        Date fromDate = result.schedule_start
        Date toDate = result.schedule_finish
        return [fromDate: fromDate, toDate: toDate]
    } else {
        println "No dates found for project: ${project}"
        return null
    }
}

// Helper method to extract byte data from the Blob
NkCurve extractBlobData(Blob blob) {
    try {
        InputStream inputStream = blob.getBinaryStream()
        byte[] byteArray = inputStream.bytes  // Converts InputStream to byte[]
        inputStream.close()

        // Print the first 50 bytes of the byte array
        println "Blob Data (first 50 bytes): " + Arrays.copyOfRange(byteArray, 0, Math.min(50, byteArray.length))

        return new NkCurve(byteArray)  // Return NkCurve created from byteArray
    } catch (Exception e) {
        println "Error extracting Blob data: ${e.message}"
        return null  // Return null in case of error
    }
}

// Method to fetch the allocation curve (soft or hard) for the resource in the given project
NkCurve getAllocationCurve(Sql sql, String project, String resource, String allocationType) {
    def query = """
        SELECT pt.${allocationType}
        FROM prteam pt
        JOIN inv_investments ii ON pt.prprojectid = ii.id
        JOIN srm_resources sr ON pt.prresourceid = sr.id
        WHERE ii.code = ?  
        AND sr.unique_name = ?
    """
    def result = sql.firstRow(query, [project, resource])

    println "Result for ${allocationType}: ${result}"

    if (result && result["${allocationType}"] instanceof Blob) {
        println "Allocation curve for ${allocationType} fetched successfully."

        // Extract bytes from Blob (instead of oracle.sql.BLOB)
        Blob blob = result["${allocationType}"]
        println "${extractBlobData(blob)}"
        return extractBlobData(blob)
    } else {
        println "No allocation curve found for ${allocationType}."
        return null
    }
}

// Method to get all resources for the given project
List<String> getResourcesForProject(Sql sql, String project) {
    def query = """
        SELECT sr.unique_name
        FROM prteam pt
        JOIN inv_investments ii ON pt.prprojectid = ii.id
        JOIN srm_resources sr ON pt.prresourceid = sr.id
        WHERE ii.code = ?
    """
    def result = sql.rows(query, [project])
    return result.collect { it.unique_name }
}

// Helper method to print the contents of the NkCurve
def printNkCurveContents(NkCurve nkCurve) {
    if (nkCurve != null && nkCurve.segments != null) {
        println "NkCurve contains ${nkCurve.segments.size()} segments:"

        nkCurve.segments.eachWithIndex { NkSegment segment, int index ->
            println "Segment ${index + 1}:"
            println "  Start Date: ${segment.startDate}"
            println "  Finish Date: ${segment.finishDate}"
            println "  Allocation Rate: ${segment.rate}"
            println "  Calendar: ${segment.calendar}"  // Assuming the calendar can be printed like this
        }
    } else {
        println "NkCurve is empty or null."
    }
}

// Method to write the allocation data to a CSV file
void writeToCSV(List<Map<String, Object>> data, String filename) {
    File file = new File(filename)
    BufferedWriter writer = new BufferedWriter(new FileWriter(file))
    writer.write("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)\n")

    data.each { entry ->
        writer.write("${entry.project},${entry.resource},${entry.segmentStart},${entry.segmentEnd},${entry.hardAllocation},${entry.softAllocation}\n")
    }

    writer.close()
}

// Method to process allocations with hard curve set to null if not found
void processAllocations(Sql sql, String project, List<String> resources, Date fromDate, Date toDate, String period) {
    List<Map<String, Object>> allocationData = []

    resources.each { resource ->
        println "Processing allocations for resource: ${resource}"

        NkCurve softCurve = getAllocationCurve(sql, project, resource, "pralloccurve")
        NkCurve hardCurve = getAllocationCurve(sql, project, resource, "hard_curve")

        // Print the contents of the fetched curves
        println "Printing Soft Allocation Curve for ${resource}:"
        printNkCurveContents(softCurve)

        println "Printing Hard Allocation Curve for ${resource}:"
        printNkCurveContents(hardCurve)

        // Handle missing soft curve
        if (softCurve == null) {
            println "No soft allocation curve found for resource ${resource}, terminating job for this resource."
            return
        }

        // Set hardCurve to null if not found
        if (hardCurve == null) {
            println "Hard allocation curve not found for resource ${resource}, setting hard allocation curve to null."
            hardCurve = null  // Explicitly set hardCurve as null
        }

        // Split the duration into segments based on the period (daily, weekly, monthly)
        use(TimeCategory) {
            Date currentDate = fromDate
            while (currentDate <= toDate) {
                def nextDate = getNextPeriod(currentDate, period)

                // Get the Soft and Hard Allocations for the current period
                def softAllocation = getAllocationForPeriod(softCurve, currentDate, nextDate)

                // If hardCurve is null, set hard allocation to 0.00 for the period
                def hardAllocation = hardCurve ? getAllocationForPeriod(hardCurve, currentDate, nextDate) : 0.00

                // Add the current period's data to the list
                allocationData.add([
                        project: project,
                        resource: resource,
                        segmentStart: currentDate.format('dd.MMM.yyyy'),
                        segmentEnd: nextDate.format('dd.MMM.yyyy'),
                        hardAllocation: hardAllocation,
                        softAllocation: softAllocation
                ])

                // Move to the next period
                currentDate = nextDate
            }
        }
    }

    // Write the data to a CSV file
    writeToCSV(allocationData, "resource_allocations_${project}_${fromDate.format('yyyyMMdd')}_${toDate.format('yyyyMMdd')}.csv")
}


// Helper method to create a default NkCurve (with 0.00 allocation)
NkCurve createDefaultNkCurve() {
    try {
        // Create a dummy NkSegment with 0.00 allocation
        // Avoid invoking complex constructors, just create a minimal dummy segment
        NkSegment dummySegment = new NkSegment(null, null, 0.0, null)

        // Return NkCurve with this dummy segment
        def defaultCurve = new NkCurve([dummySegment])
        return defaultCurve
    } catch (Exception e) {
        println "Error creating default NkCurve: ${e.message}"
        return new NkCurve([])  // Return an empty curve if error occurs
    }
}

// Method to get the next period end date based on the period type (daily, weekly, monthly)
Date getNextPeriod(Date currentDate, String period) {
    switch (period.toLowerCase()) {
        case "daily":
            return currentDate + 1.day
        case "weekly":
            return currentDate + 1.week
        case "monthly":
            return currentDate + 1.month
        case "quarterly":
            return currentDate + 3.month
        default:
            throw new IllegalArgumentException("Unknown period type: ${period}")
    }
}

// Method to get the allocation value for the given period
Double getAllocationForPeriod(NkCurve curve, Date startDate, Date endDate) {
    Double totalAllocation = 0.0
    println "Checking allocation for period: ${startDate.format('dd.MMM.yyyy')} - ${endDate.format('dd.MMM.yyyy')}"

    curve?.segments?.each { NkSegment segment ->
        println "Segment Start: ${segment.startDate}, Segment End: ${segment.finishDate}, Rate: ${segment.rate}"

        // Adjust the date comparison logic to handle potential overlaps more accurately.
        boolean overlap = (segment.startDate >= startDate && segment.startDate <= endDate) ||
                (segment.finishDate >= startDate && segment.finishDate <= endDate) ||
                (segment.startDate <= startDate && segment.finishDate >= endDate)

        if (overlap) {
            totalAllocation += segment.rate
        }
    }

    // Log the total allocation for the period
    println "Total Allocation for the period (${startDate.format('dd.MMM.yyyy')} - ${endDate.format('dd.MMM.yyyy')}): ${totalAllocation} hours"

    return totalAllocation ?: 0.00  // Return 0.00 if no allocation is found or curve is null
}

// Fetch the project dates from the database
def projectDates = getProjectDates(sql, project)
if (projectDates) {
    Date fromDate = projectDates.fromDate
    Date toDate = projectDates.toDate

    // Get all resources for the project
    List<String> resources = getResourcesForProject(sql, project)

    // Run the allocation migration job for all resources
    processAllocations(sql, project, resources, fromDate, toDate, period)
}

// Close the database connection after use
connection.close()