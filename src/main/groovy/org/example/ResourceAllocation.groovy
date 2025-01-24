package org.example

import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import com.niku.xmlserver.core.NkTime
import groovy.sql.Sql
import groovy.time.TimeCategory
import java.sql.DriverManager
import java.sql.Connection
import java.sql.Blob
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.LocalDate

def dbUrl = "jdbc:oracle:thin:@//10.0.0.35:11521/clarity" // Replace with your DB details
def dbUser = "niku"
def dbPassword = "niku"

Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
def sql = new Sql(connection)
def projectCode = "PR1016"
def periodType = "monthly"

Map<String, Date> getProjectSchedule(Sql sql, String projectCode) {
    def query = """
        SELECT schedule_start, schedule_finish
        FROM inv_investments
        WHERE code = ?
    """
    def result = sql.firstRow(query, [projectCode])

    if (result) {
        Date startDate = result.schedule_start
        Date endDate = result.schedule_finish
        return [startDate: startDate, endDate: endDate]
    } else {
        println "No schedule found for project: ${projectCode}"
        return null
    }
}

NkCurve extractCurveFromBlob(Blob blob) {
    try {
        InputStream inputStream = blob.getBinaryStream()
        byte[] byteArray = inputStream.bytes  // Converts InputStream to byte[]
        inputStream.close()
        return new NkCurve(byteArray)  // Return NkCurve created from byteArray
    } catch (Exception e) {
        println "Error extracting Blob data: ${e.message}"
        return null
    }
}

Map<String, NkCurve> getAllocationCurves(Sql sql, String projectCode, String resource) {
    def allocationCurves = [:]
    allocationCurves['softCurve'] = getAllocationCurve(sql, projectCode, resource, "pralloccurve")
    allocationCurves['hardCurve'] = getAllocationCurve(sql, projectCode, resource, "hard_curve")
    return allocationCurves
}

// Function to clone and optimize the segments
def cloneOptimizedSegments(byte[] bytes) {
    NkCurve clonedCurve = new NkCurve(1)

    if (bytes) {
        def curve = new NkCurve(bytes)
        curve.segments.each { NkSegment segment ->
            def start = DateUtil.ensureMidnightStart(segment.startDate)
            def finish = DateUtil.ensureMidnightFinish(segment.finishDate)
            def startDate = start.toLocalDate()
            def endDate = finish.toLocalDate()
            def firstOfMonth = startDate.withDayOfMonth(1)
            def lastOfMonth = startDate.withDayOfMonth(startDate.lengthOfMonth()).plusDays(1)

            while (firstOfMonth.isBefore(endDate)) {
                def startSegment = NkTime.toNkTime((firstOfMonth.isBefore(startDate) ? startDate : firstOfMonth).toDate())
                def finishSegment = NkTime.toNkTime((endDate.isBefore(lastOfMonth) ? endDate : lastOfMonth).toDate())
                def rate = segment.rate
                if (rate > 0.0D) {
                    clonedCurve.segments.setSegment(startSegment, finishSegment, rate, null)
                }
                firstOfMonth = firstOfMonth.plus(1, ChronoUnit.MONTHS)
                lastOfMonth = firstOfMonth.withDayOfMonth(firstOfMonth.lengthOfMonth()).plusDays(1)
            }
        }
    }

    return clonedCurve
}

// Function to filter segments by periods
NkCurve getFilterSegments(NkCurve curve, Date start, Integer periods, String periodType) {
    println "Filtering curve for periods starting from $start with $periods $periodType periods."

    if (!curve) {
        println "Error: curve is null, cannot filter."
        return null
    }

    NkCurve filteredCurve = new NkCurve(1) // Create a new curve to store filtered segments
    //List<List<String>> csvData = [] // List to store the CSV rows

    // Add the CSV headers
    //csvData << ["Project","Resource", "Period Start", "Period End", "Segment Start", "Segment End", "Rate"]

    use(TimeCategory) {
        for (int i = 0; i < periods; i++) {
            def periodStartDate
            def periodEndDate

            // Calculate period start and end date based on period type
            switch (periodType) {
                case "MONTHLY":
                    periodStartDate = start + i.month
                    periodEndDate = start + (i + 1).month
                    break
                case "QUARTERLY":
                    periodStartDate = start + (i * 3).months
                    periodEndDate = start + ((i + 1) * 3).months
                    break
                case "YEARLY":
                    periodStartDate = start + (i * 12).months
                    periodEndDate = start + ((i + 1) * 12).months
                    break
                default:
                    throw new Exception("Invalid period type: $periodType")
            }
            def periodSumm = curve.getSum(periodStartDate, periodEndDate)
            println "Period Sum for before${periodStartDate} to ${periodEndDate}: $periodSumm"
            def nkPeriodStartDate = NkTime.toNkTime(periodStartDate)
            def nkPeriodEndDate = NkTime.toNkTime(periodEndDate)
            //println "Processing $periodType period: ${i + 1}, Start: $periodStartDate, End: $periodEndDate"
            //def periodSum = curve.getSum(nkPeriodStartDate, nkPeriodEndDate)

            // println "Period Sum for ${periodStartDate} to ${periodEndDate}: $periodSum"

//            def row = [
//                    "Project",
//                    "sourceTeamData.PRRESOURCEID",
//                    periodStartDate,
//                    periodEndDate,
//                    "segment.rate",
//                    convertToPersonDays(curve.segments.getSum(nkPeriodStartDate, nkPeriodEndDate))
//            ]
            //csvData << row

            // Filter out the segments that fall within this period
            //curve.segments.each {NkSegment s -> println(s)}
            curve.segments.each { NkSegment segment ->
                if (segment.startDate >= periodStartDate && segment.finishDate <= periodEndDate) {
                    filteredCurve.segments.setSegment(segment)
                }

            }
            // If no segments were added for this period, create a default segment with 0 value
            println " the size of the filter curve is ${filteredCurve.segments.size()}"
            if (filteredCurve.segments.size() == 0) {
                filteredCurve.segments.setSegment(NkTime.toNkTime(periodStartDate), NkTime.toNkTime(periodEndDate), 0.0D, null)
//                def periodSum = curve.segments.addSegment(nkPeriodStartDate, nkPeriodEndDate, 1, null)
                println "No segments found for this period. Added default segment with 0 allocation."
            }
        }
    }

    // println "Filtered Curve: $filteredCurve"
    return filteredCurve
}
int calculateWorkdaysBetween(Date startDate, Date endDate) {
    LocalDate start = startDate.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    LocalDate end = endDate.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    int workdays = 0
    // Loop through the range from start to end date
    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
        if (date.getDayOfWeek().getValue() <= 5) {  // 1 = Monday, 5 = Friday
            workdays++
        }
    }
    return workdays
}

// Example usage to get the filtered curve for both soft and hard allocation
def periods = 6
def fromDate = Date.parse('yyyy-MM-dd', '2025-01-01')

Map<String, NkCurve> allocationCurves = getAllocationCurves(sql, projectCode, "yourResource")

NkCurve softCurve = allocationCurves['softCurve']
NkCurve hardCurve = allocationCurves['hardCurve']

// Method to calculate Person Working Days for a given split curve
double calculatePersonWorkingDays(NkCurve curve) {
    double totalPersonDays = 0.0
    curve.segments.each { NkSegment segment ->
        int workdaysInSegment = calculateWorkdaysBetween(segment.startDate, segment.finishDate)
        def value = segment.rate  // Example, replace with actual value retrieval
        def totalHours = value / 3600
        def personDays = totalHours / 8
        double adjustedPersonDays = personDays * workdaysInSegment
        totalPersonDays += adjustedPersonDays
    }
    long roundedPersonDays = Math.round(totalPersonDays)

    return roundedPersonDays
}

NkCurve getAllocationCurve(Sql sql, String projectCode, String resource, String allocationType) {
    def query = """
        SELECT pt.${allocationType}
        FROM prteam pt
        JOIN inv_investments ii ON pt.prprojectid = ii.id
        JOIN srm_resources sr ON pt.prresourceid = sr.id
        WHERE ii.code = ?  
        AND sr.unique_name = ?
    """
    def result = sql.firstRow(query, [projectCode, resource])
    if (result && result[allocationType] instanceof Blob) {
        return extractCurveFromBlob(result[allocationType])
    } else {
        return null
    }
}

List<String> getProjectResources(Sql sql, String projectCode) {
    def query = """
        SELECT sr.unique_name
        FROM prteam pt
        JOIN inv_investments ii ON pt.prprojectid = ii.id
        JOIN srm_resources sr ON pt.prresourceid = sr.id
        WHERE ii.code = ?
    """
    return sql.rows(query, [projectCode]).collect { it.unique_name }
}

void printCurveDetails(NkCurve curve) {
    if (curve?.segments) {
        println "NkCurve contains ${curve.segments.size()} segments:"
        curve.segments.eachWithIndex { NkSegment segment, int index ->
            println "Segment ${index + 1}: Start Date: ${segment.startDate}, End Date: ${segment.finishDate}, Rate: ${segment.rate}"
        }
    } else {
        println "The NkCurve is empty or null."
    }
}
Integer getNumberOfPeriods(Date startDate, Date endDate, String period) {
    println "Calculating periods between $startDate and $endDate for period type: $period"

    Calendar startCal = Calendar.getInstance()
    Calendar endCal = Calendar.getInstance()
    startCal.setTime(startDate)
    endCal.setTime(endDate)

    int yearsDiff = endCal.get(Calendar.YEAR) - startCal.get(Calendar.YEAR)
    int monthsDiff = endCal.get(Calendar.MONTH) - startCal.get(Calendar.MONTH)

    if (monthsDiff < 0) {
        yearsDiff--
        monthsDiff += 12
    }

    int totalMonthsDiff = (yearsDiff * 12) + monthsDiff
    int periods = 0

    switch (period) {
        case "MONTHLY":
            periods = totalMonthsDiff
            break
        case "QUARTERLY":
            periods = Math.ceil(totalMonthsDiff / 3.0) as Integer
            break
        case "YEARLY":
            periods = yearsDiff + (monthsDiff > 0 ? 1 : 0) // If there are remaining months, add one more period
            break
        default:
            throw new Exception("Invalid period type: $period")
    }

    println "Total periods: $periods"
    return periods
}
void exportToCSV(List<Map<String, Object>> data, String filename) {
    File file = new File(filename)
    BufferedWriter writer = new BufferedWriter(new FileWriter(file))
    writer.write("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)\n")
    data.each { entry ->
        writer.write("${entry.project},${entry.resource},${entry.segmentStart},${entry.segmentEnd},${entry.hardAllocation},${entry.softAllocation}\n")
    }
    writer.close()
}

void processAllocations(Sql sql, String projectCode, List<String> resources, Date fromDate, Date toDate, String periodType) {
    List<Map<String, Object>> allocationData = []

    resources.each { resource ->
        println "Processing allocations for resource: ${resource}"

        Map<String, NkCurve> allocationCurves = getAllocationCurves(sql, projectCode, resource)

        NkCurve softCurve = allocationCurves['softCurve']
        NkCurve hardCurve = allocationCurves['hardCurve']

        printCurveDetails(softCurve)
        printCurveDetails(hardCurve)

        if (softCurve == null) {
            println "No soft allocation curve found for resource ${resource}, skipping this resource."
            return
        }

        if (hardCurve == null) {
            println "No hard allocation curve found for resource ${resource}, setting hard allocation to null."
            hardCurve = null
        }
        use(TimeCategory) {
            Date currentDate = fromDate
            while (currentDate <= toDate) {
                def nextDate = calculateNextPeriod(currentDate, periodType)
                def softAlloc = calculateAllocationForPeriod(softCurve, currentDate, nextDate)
                def hardAlloc = hardCurve ? calculateAllocationForPeriod(hardCurve, currentDate, nextDate) : 0.00
                allocationData.add([project       : projectCode, resource: resource,
                                    segmentStart  : currentDate.format('dd.MMM.yyyy'),
                                    segmentEnd    : nextDate.format('dd.MMM.yyyy'),
                                    hardAllocation: hardAlloc, softAllocation: softAlloc])
                currentDate = nextDate
            }
        }
    }

    exportToCSV(allocationData, "allocations_${projectCode}_${fromDate.format('yyyyMMdd')}_${toDate.format('yyyyMMdd')}.csv")
}

Date calculateNextPeriod(Date currentDate, String periodType) {
    switch (periodType.toLowerCase()) {
        case "daily":
            return currentDate + 1.day
        case "weekly":
            return currentDate + 1.week
        case "monthly":
            return currentDate + 1.month
        case "quarterly":
            return currentDate + 3.month
        default:
            throw new IllegalArgumentException("Unknown period type: ${periodType}")
    }
}

Double calculateAllocationForPeriod(NkCurve curve, Date startDate, Date endDate) {
    Double totalAllocation = 0.0
    curve?.segments?.each { NkSegment segment ->
        boolean isOverlapping = (segment.startDate >= startDate && segment.startDate <= endDate) ||
                (segment.finishDate >= startDate && segment.finishDate <= endDate) ||
                (segment.startDate <= startDate && segment.finishDate >= endDate)

        if (isOverlapping) {
            totalAllocation += segment.rate
        }
    }
    return totalAllocation ?: 0.00
}

def projectSchedule = getProjectSchedule(sql, projectCode)
if (projectSchedule) {
    Date sDate = projectSchedule.startDate
    Date toDate = projectSchedule.endDate
    List<String> projectResources = getProjectResources(sql, projectCode)
    processAllocations(sql, projectCode, projectResources, sDate, toDate, periodType)
}

connection.close()
