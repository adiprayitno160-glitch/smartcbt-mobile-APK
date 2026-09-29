const http = require('http');
const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET || 'cbt_prod_secret_f9a83b27c64e10d592a8174fec01849a62bc38102d7e481b9201948271039841';
const token = jwt.sign({ id: 'admin-1', username: 'admin', role: 'ADMIN' }, JWT_SECRET, { expiresIn: '1h' });

function testEndpoint(path) {
  return new Promise((resolve) => {
    const options = {
      hostname: 'localhost',
      port: 3000,
      path: path,
      method: 'GET',
      headers: {
        'Authorization': 'Bearer ' + token
      }
    };
    http.get(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          resolve({ status: res.statusCode, data: json });
        } catch(e) {
          resolve({ status: res.statusCode, text: data.substring(0, 100) });
        }
      });
    }).on('error', err => {
      resolve({ error: err.message });
    });
  });
}

async function run() {
  console.log('Testing /api/attendance/rekap-periode (mode=MONTHLY, year=2026, month=9)...');
  const rekapMonth = await testEndpoint('/api/attendance/rekap-periode?mode=MONTHLY&year=2026&month=9');
  console.log('Rekap Month Status:', rekapMonth.status);
  if (rekapMonth.data) {
    console.log('Success:', rekapMonth.data.success);
    console.log('Mode:', rekapMonth.data.mode);
    console.log('Periode:', rekapMonth.data.periodeLabel);
    console.log('Hari Efektif:', rekapMonth.data.hariEfektif);
    console.log('Total Siswa Records:', rekapMonth.data.records?.length);
    console.log('Summary Counts:', rekapMonth.data.counts);
    console.log('Class Comparison count:', rekapMonth.data.classComparison?.length);
    if (rekapMonth.data.records && rekapMonth.data.records.length > 0) {
      const firstStudent = rekapMonth.data.records[0];
      console.log('Sample Student (Per Siswa 1 Bulan):', {
        name: firstStudent.name,
        class: firstStudent.className,
        H: firstStudent.H,
        T: firstStudent.T,
        S: firstStudent.S,
        I: firstStudent.I,
        A: firstStudent.A,
        lateMinutes: firstStudent.lateMinutes + 'm',
        presenceRate: firstStudent.presenceRate + '%'
      });

      console.log('\nTesting /api/attendance/rekap-siswa/' + firstStudent.userId + ' (Kalender Harian Siswa)...');
      const studentDetail = await testEndpoint('/api/attendance/rekap-siswa/' + firstStudent.userId + '?month=9&year=2026');
      console.log('Student Detail Status:', studentDetail.status);
      if (studentDetail.data && studentDetail.data.success) {
        console.log('Student Info:', studentDetail.data.student?.name, '(', studentDetail.data.student?.className, ')');
        console.log('Student Summary:', studentDetail.data.summary);
        console.log('Total Days in Calendar:', studentDetail.data.dailyCalendar?.length);
        if (studentDetail.data.dailyCalendar && studentDetail.data.dailyCalendar.length > 0) {
          console.log('Sample Calendar Days:', studentDetail.data.dailyCalendar.slice(0, 3));
        }
      }
    }
  }

  console.log('\nTesting /api/attendance/rekap-periode (mode=WEEKLY)...');
  const rekapWeek = await testEndpoint('/api/attendance/rekap-periode?mode=WEEKLY&date=2026-09-29');
  console.log('Rekap Week Status:', rekapWeek.status);
  if (rekapWeek.data) {
    console.log('Week Periode:', rekapWeek.data.periodeLabel);
    console.log('Week Summary Counts:', rekapWeek.data.counts);
    console.log('Daily Trends count:', rekapWeek.data.dailyTrends?.length);
  }
}

run();
