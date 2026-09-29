async function testBoth() {
  const targets = [
    { name: "LOCAL (http://localhost:3000)", base: "http://localhost:3000" },
    { name: "ONLINE VPS (https://cbt.smpn1boyolangu.my.id)", base: "https://cbt.smpn1boyolangu.my.id" }
  ];

  const credentials = [
    { u: "GURU-604442", p: "password123" },
    { u: "GURU-604442", p: "guru123" },
    { u: "guru-604442", p: "password123" },
    { u: "604442", p: "password123" },
    { u: "ARIF FAOZAN", p: "password123" },
    { u: "199408022025211129", p: "guru123" },
    { u: "guru01", p: "password123" },
    { u: "guru1", p: "password123" }
  ];

  for (const t of targets) {
    console.log(`\n=== Testing ${t.name} ===`);
    for (const c of credentials) {
      try {
        const res = await fetch(`${t.base}/api/auth/login`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username: c.u, password: c.p })
        });
        const data = await res.json();
        console.log(`[${res.status}] User: "${c.u}" | Pass: "${c.p}" => ${data.message || (data.user && `${data.user.name} (${data.user.role})`)}`);
      } catch (err) {
        console.log(`Error connecting to ${t.name}:`, err.message);
        break;
      }
    }
  }
}

testBoth();
