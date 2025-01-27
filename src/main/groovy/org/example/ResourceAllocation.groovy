package org.example

import com.niku.xmlserver.blob.NkCalendar
import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import com.niku.xmlserver.core.NkDate
import com.niku.xmlserver.core.NkTime
import de.itdesign.clarity.logging.CommonLogger
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.time.TimeCategory

import groovy.transform.Field

import java.sql.Blob
import java.sql.Connection
import java.sql.DriverManager
import java.text.SimpleDateFormat

def dbUrl = "jdbc:oracle:thin:@//10.0.0.35:11521/clarity"
def dbUser = "niku"
def dbPassword = "niku"


Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
sql = new Sql(connection)

@Field CommonLogger cmnLog = new CommonLogger(this)
cmnLog.setFailJobOnError(true)

// Main Execution Flow
def runExport() {
    cmnLog.info "Started exporting allocations at:-${new Date()}"
    try {
        assertParameters()
    } catch (Exception e) {
        cmnLog.error("Error occurred: ${e.message}", e)
        throw e
    }
    cmnLog.info "Finished exporting allocations at:${new Date()}"
}

void assertParameters() {
    cmnLog.info "Parameters passed to the job: [Target CI Type: ${binding.variables.get('z_project_name')}, Target PF Code: ${binding.variables.get('z_resource_name')}, From: ${binding.variables.get('z_from_date')}, To: ${binding.variables.get('z_to_date')}]"


    FROM_DATE = parseDate(binding.variables.get('z_from_date'))
    TO_DATE = parseDate(binding.variables.get('z_to_date'))
    validateDateRange(FROM_DATE, TO_DATE)

    PROJECT = binding.variables.get('z_project')
    RESOURCE = binding.variables.get('z_resource')
    PERIOD = binding.variables.get('z_period')

    cmnLog.info "Project:-${PROJECT}, Resource:-${RESOURCE}, Period:-${PERIOD}"

    def PROJECT = "5001123"
    def PERIOD = "MONTHLY"
    def FROM_DATE = parseDate("2024-3-2 12:00:00")
    def TO_DATE = parseDate("2025-8-30 12:00:00")
    def RESOURCE = "5004002"

    generateAllocationCsv(PROJECT as String, RESOURCE as String, FROM_DATE, TO_DATE, PERIOD as String)
}

Date parseDate(String dateStr) {
    if (dateStr) {
        def formattedString = dateStr.replace("T", " ")
        return Date.parse("yyyy-MM-dd HH:mm:ss", formattedString)
    }
    return null
}


void validateDateRange(Date fromDate, Date toDate) {
    if (fromDate != null && toDate != null && fromDate.after(toDate)) {
        throw new Exception("Invalid date range: From date cannot be after To date.")
    }
}

def generateAllocationCsv(String projectId, String resourceId, Date fromDate, Date toDate, String period) {
    GroovyRowResult investment = getProject(projectId)
    Integer periods = getNumberOfPeriods(fromDate, toDate, period)
    GroovyRowResult teamData = getResource(investment.id as String, resourceId)

    NkCurve softCurve = getCurveFromBlob(teamData, "PRALLOCCURVE")
    NkCurve hardCurve = getCurveFromBlob(teamData, "HARD_CURVE")
    println "curve"
    println "${softCurve}"
    if (softCurve) {
        NkCurve filteredSoftCurve = getFilterSegments(softCurve, fromDate, periods, period)
        println "filteredSoftCurve"
        println "${filteredSoftCurve}"
        createCsvAndWriteToFile(filteredSoftCurve, investment, teamData, softCurve, hardCurve, fromDate, toDate, period)
    }

    if (hardCurve) {
        NkCurve filteredHardCurve = getFilterSegments(hardCurve, fromDate, periods, period)
        createCsvAndWriteToFile(filteredHardCurve, investment, teamData, softCurve, hardCurve, fromDate, toDate, period)
    }
}


def createCsvAndWriteToFile(NkCurve curve, GroovyRowResult investment, GroovyRowResult teamData, NkCurve softCurve, NkCurve hardCurve, Date fromDate, Date toDate, String period) {
    List<List<String>> csvData = []
    def periodIncrement, periodLength
    use(TimeCategory) {
        switch (period) {
            case "MONTHLY":
                periodIncrement = 1.month
                periodLength = 1.month - 1.day
                break
            case "QUARTERLY":
                periodIncrement = 3.month
                periodLength = 3.month - 1.day
                break
            case "YEARLY":
                periodIncrement = 1.year
                periodLength = 1.year - 1.day
                break
            default:
                throw new IllegalArgumentException("Unsupported period: $period")
        }

        def formatDate = new SimpleDateFormat("dd.MMM.yyyy")
        def currentStartDate = fromDate
        while (currentStartDate <= toDate) {
            def currentEndDate = currentStartDate + periodLength
            currentEndDate = currentEndDate > toDate ? toDate : currentEndDate

            def days = getPersonDays(curve, currentStartDate, currentEndDate, period)
            def formatStartDate = formatDate.format(currentStartDate)
            def formatEndDate = formatDate.format(currentEndDate)
            def row = [
                    investment.name,
                    teamData.PRRESOURCEID,
                    formatStartDate,
                    formatEndDate,
                    softCurve ? days : 0,
                    hardCurve ? days : 0
            ]
            csvData << row

            currentStartDate = currentStartDate + periodIncrement
        }

        writeToCSV(csvData, "allocation_output.csv")
    }
}

def writeToCSV(List<List<String>> data, String outputFile) {
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


GroovyRowResult getProject(String projectId) {
    def query = """SELECT id, code, name, schedule_start, schedule_finish FROM inv_investments WHERE id = ?"""
    return sql.firstRow(query, [projectId])
}

GroovyRowResult getResource(String projectId, String resourceId) {
    def query = """SELECT team.PRUID, team.prid, team.PRRESOURCEID, team.PRALLOCCURVE, team.HARD_CURVE 
                   FROM prteam team
                   INNER JOIN inv_investments ii ON ii.id = team.prprojectid
                   WHERE ii.id = ? AND team.PRRESOURCEID = ?"""
    return sql.firstRow(query, [projectId, resourceId])
}

NkCurve getCurveFromBlob(GroovyRowResult teamData, String curveName) {
    println "Converting Blob to NkCurve for curve: $curveName"
    Blob curveBlob = teamData?.get(curveName) as Blob
    if (!curveBlob) {
        println "Error: Curve Blob for $curveName is null."
        return null
    }
    byte[] curveBytes = curveBlob ? curveBlob.getBytes(1, (int) curveBlob.length()) : null
    NkCurve curve = curveBytes ? new NkCurve(curveBytes) : null
    if (!curve) {
        println "Error: Failed to create NkCurve from Blob for $curveName."
    }
    return curve
}

NkCurve getFilterSegments(NkCurve curve, Date start, Integer periods, String periodType) {
    println "Filtering curve for periods starting from $start with $periods $periodType periods."
    if (!curve) {
        println "Error: curve is null, cannot filter."
        return null
    }
    NkCurve filteredCurve = new NkCurve(1)
    use(TimeCategory) {
        for (int i = 0; i < periods; i++) {

            def periodStartDate
            def periodEndDate
            switch (periodType) {
                case "MONTHLY":

                    periodStartDate = start + i.month
                    periodEndDate = start + (i + 1).month - 1.day  // Ensure correct end date for monthly
                    break
                case "QUARTERLY":

                    periodStartDate = start + (i * 3).months
                    periodEndDate = start + ((i + 1) * 3).months - 1.day  // Adjust for quarterly periods
                    break
                case "YEARLY":

                    periodStartDate = start + (i * 12).months
                    periodEndDate = start + ((i + 1) * 12).months - 1.day  // Adjust for yearly periods
                    break
                default:
                    throw new Exception("Invalid period type: $periodType")
            }

            println "Period $i: Start Date: $periodStartDate, End Date: $periodEndDate"


            curve.segments.each { NkSegment segment ->
                if (segment.startDate >= periodStartDate && segment.finishDate <= periodEndDate) {
                    filteredCurve.segments.setSegment(segment)
                }
            }

            println "The size of the filtered curve is ${filteredCurve.segments.size()}"
            if (filteredCurve.segments.size() == 0) {
                filteredCurve.segments.setSegment(NkTime.toNkTime(periodStartDate), NkTime.toNkTime(periodEndDate), 0.0D, null)
                println "No segments found for this period. Added default segment with 0 allocation."
            }
        }
    }
    return filteredCurve
}


Integer getNumberOfPeriods(Date startDate, Date endDate, String period) {
    Calendar startCal = Calendar.getInstance()
    Calendar endCal = Calendar.getInstance()
    startCal.setTime(startDate)
    endCal.setTime(endDate)

    int yearsDiff = endCal.get(Calendar.YEAR) - startCal.get(Calendar.YEAR)
    int monthsDiff = endCal.get(Calendar.MONTH) - startCal.get(Calendar.MONTH)
    if (monthsDiff < 0) {
        yearsDiff--; monthsDiff += 12
    }

    int totalMonthsDiff = (yearsDiff * 12) + monthsDiff
    switch (period) {
        case "MONTHLY": return totalMonthsDiff
        case "QUARTERLY": return Math.ceil(totalMonthsDiff / 3.0) as Integer
        case "YEARLY": return yearsDiff + (monthsDiff > 0 ? 1 : 0)
        default: throw new Exception("Invalid period type: $period")
    }
}

def getPersonDays(NkCurve curve, Date fromDate, Date toDate, String period) {
    def days = 0
    NkCalendar calendar = new NkCalendar()

    switch (period) {
        case "MONTHLY":
            def nkPeriodStartDate = NkTime.toNkTime(fromDate)
            def updatedDate = nkPeriodStartDate.add(2 * 24 * 60 * 60)
            def rate = curve.segments.getRate(updatedDate)
            def startDate = new NkDate(fromDate, false)
            def endDate = new NkDate(toDate, false)
            def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
            days = (rate * diffWorkingDays).round(2)
            break
        case "QUARTERLY":
            def nkPeriodStartDate = NkTime.toNkTime(fromDate)
            def rate = curve.segments.getRate(nkPeriodStartDate)
            def startDate = new NkDate(fromDate, false)
            def endDate = new NkDate(toDate, false)
            def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
            days = (rate * diffWorkingDays).round(2)
            break
        case "YEARLY":
            def nkPeriodStartDate = NkTime.toNkTime(fromDate)
            def rate = curve.segments.getRate(nkPeriodStartDate)
            def startDate = new NkDate(fromDate, false)
            def endDate = new NkDate(toDate, false)
            def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
            days = (rate * diffWorkingDays).round(2)
            break
        default:
            throw new IllegalArgumentException("Unsupported period: $period")
    }
    return days
}

runExport()
