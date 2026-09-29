import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = process.env.SUPABASE_URL || 'https://lgcbhzyfnbspdxrcxyon.supabase.co';
const SUPABASE_KEY = process.env.SUPABASE_KEY || 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxnY2JoenlmbmJzcGR4cmN4eW9uIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc4MTY5ODM3NiwiZXhwIjoyMDk3Mjc0Mzc2fQ.IH5ZX2w7WalHuriHjoFoOURXy7HpzM0A1dO_pfyHvu0';

export const supabase = createClient(SUPABASE_URL, SUPABASE_KEY);
