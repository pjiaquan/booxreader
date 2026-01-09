import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createClient } from "@supabase/supabase-js";
import { z } from "zod";

// 1. 初始化 Supabase (Lazy)
let supabase = null;
function getSupabase() {
    if (supabase) return supabase;
    const supabaseUrl = process.env.SUPABASE_URL;
    const supabaseKey = process.env.SUPABASE_KEY;

    if (!supabaseUrl || !supabaseKey) {
        throw new Error("Missing SUPABASE_URL or SUPABASE_KEY");
    }
    supabase = createClient(supabaseUrl, supabaseKey);
    return supabase;
}

// 2. 初始化 MCP Server
const server = new McpServer({
    name: "supabase-http-server",
    version: "1.0.0",
});

// 3. 註冊工具 (Tool): 執行 SQL Migration
// 使用最新的 registerTool API
server.registerTool(
    "execute_migration_sql",
    {
        description: "Executes a SQL command via Supabase RPC 'exec_sql'. Use this for creating tables, altering columns, or migration tasks.",
        inputSchema: {
            sql: z.string().describe("The raw SQL to execute for migration or DDL"),
        },
    },
    async ({ sql }) => {
        const client = getSupabase();
        const { data, error } = await client.rpc("exec_sql", { query: sql });

        if (error) {
            return {
                content: [{ type: "text", text: `Error: ${error.message}` }],
                isError: true,
            };
        }
        return {
            content: [{ type: "text", text: `Success: ${JSON.stringify(data)}` }],
        };
    }
);

// 4. 註冊工具 (Tool): 查看資料表
server.registerTool(
    "inspect_table",
    {
        description: "Read data from a table to understand its schema and content.",
        inputSchema: {
            tableName: z.string().describe("Name of the table to inspect"),
            limit: z.number().default(5).describe("Max rows to fetch"),
        },
    },
    async ({ tableName, limit }) => {
        const client = getSupabase();
        const { data, error } = await client
            .from(tableName)
            .select("*")
            .limit(limit);

        if (error) {
            return {
                content: [{ type: "text", text: `Error: ${error.message}` }],
                isError: true,
            };
        }
        return {
            content: [{ type: "text", text: JSON.stringify(data, null, 2) }],
        };
    }
);


// 5. 啟動伺服器
async function run() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
    console.error("Supabase MCP Server running on stdio");
}

run().catch(console.error);