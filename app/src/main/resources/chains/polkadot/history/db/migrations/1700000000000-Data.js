module.exports = class Data1700000000000 {
    name = 'Data1700000000000'

    async up(db) {
        await db.query(`CREATE TABLE "block" ("id" character varying NOT NULL, "height" integer NOT NULL, "hash" text NOT NULL, "parent_hash" text NOT NULL, "timestamp" TIMESTAMP WITH TIME ZONE NOT NULL, "spec_version" integer NOT NULL, "extrinsic_count" integer NOT NULL, "event_count" integer NOT NULL, CONSTRAINT "PK_d0925763efb591c2e2ffb267572" PRIMARY KEY ("id"))`)
        await db.query(`CREATE INDEX "idx_block_height_b20720ad" ON "block" ("height") `)
        await db.query(`CREATE INDEX "idx_block_hash_575104b7" ON "block" ("hash") `)
        await db.query(`CREATE INDEX "idx_block_timestamp_82806c7c" ON "block" ("timestamp") `)
        await db.query(`CREATE TABLE "extrinsic" ("id" character varying NOT NULL, "block_number" integer NOT NULL, "index" integer NOT NULL, "hash" text NOT NULL, "pallet" text NOT NULL, "call" text NOT NULL, "signer" text, "success" boolean NOT NULL, "tip" numeric, "timestamp" TIMESTAMP WITH TIME ZONE NOT NULL, "args" jsonb, CONSTRAINT "PK_80d7db0e4b1e83e30336bc76755" PRIMARY KEY ("id"))`)
        await db.query(`CREATE INDEX "idx_extrinsic_block_number_d3414dc1" ON "extrinsic" ("block_number") `)
        await db.query(`CREATE INDEX "idx_extrinsic_hash_fb7b0484" ON "extrinsic" ("hash") `)
        await db.query(`CREATE INDEX "idx_extrinsic_timestamp_5e15c657" ON "extrinsic" ("timestamp") `)
        await db.query(`CREATE INDEX "idx_extrinsic_pallet_call_block_number_3452bb14" ON "extrinsic" ("pallet", "call", "block_number") `)
        await db.query(`CREATE INDEX "idx_extrinsic_signer_block_number_fc616dec" ON "extrinsic" ("signer", "block_number") `)
        await db.query(`CREATE TABLE "event" ("id" character varying NOT NULL, "block_number" integer NOT NULL, "index" integer NOT NULL, "extrinsic_index" integer, "phase" text NOT NULL, "pallet" text NOT NULL, "name" text NOT NULL, "timestamp" TIMESTAMP WITH TIME ZONE NOT NULL, "args" jsonb, CONSTRAINT "PK_30c2f3bbaf6d34a55f8ae6e4614" PRIMARY KEY ("id"))`)
        await db.query(`CREATE INDEX "idx_event_block_number_367674b2" ON "event" ("block_number") `)
        await db.query(`CREATE INDEX "idx_event_pallet_name_block_number_5ec2090c" ON "event" ("pallet", "name", "block_number") `)
        await db.query(`CREATE TABLE "transfer" ("id" character varying NOT NULL, "block_number" integer NOT NULL, "timestamp" TIMESTAMP WITH TIME ZONE NOT NULL, "extrinsic_hash" text, "extrinsic_index" integer, "event_index" integer NOT NULL, "from" text NOT NULL, "to" text NOT NULL, "amount" numeric NOT NULL, CONSTRAINT "PK_fd9ddbdd49a17afcbe014401295" PRIMARY KEY ("id"))`)
        await db.query(`CREATE INDEX "idx_transfer_block_number_16bfc223" ON "transfer" ("block_number") `)
        await db.query(`CREATE INDEX "idx_transfer_timestamp_cc1edef1" ON "transfer" ("timestamp") `)
        await db.query(`CREATE INDEX "idx_transfer_extrinsic_hash_d2a2fdff" ON "transfer" ("extrinsic_hash") `)
        await db.query(`CREATE INDEX "idx_transfer_to_block_number_12873b69" ON "transfer" ("to", "block_number") `)
        await db.query(`CREATE INDEX "idx_transfer_from_block_number_28d4a37d" ON "transfer" ("from", "block_number") `)
    }

    async down(db) {
        await db.query(`DROP INDEX "public"."idx_transfer_from_block_number_28d4a37d"`)
        await db.query(`DROP INDEX "public"."idx_transfer_to_block_number_12873b69"`)
        await db.query(`DROP INDEX "public"."idx_transfer_extrinsic_hash_d2a2fdff"`)
        await db.query(`DROP INDEX "public"."idx_transfer_timestamp_cc1edef1"`)
        await db.query(`DROP INDEX "public"."idx_transfer_block_number_16bfc223"`)
        await db.query(`DROP TABLE "transfer"`)
        await db.query(`DROP INDEX "public"."idx_event_pallet_name_block_number_5ec2090c"`)
        await db.query(`DROP INDEX "public"."idx_event_block_number_367674b2"`)
        await db.query(`DROP TABLE "event"`)
        await db.query(`DROP INDEX "public"."idx_extrinsic_signer_block_number_fc616dec"`)
        await db.query(`DROP INDEX "public"."idx_extrinsic_pallet_call_block_number_3452bb14"`)
        await db.query(`DROP INDEX "public"."idx_extrinsic_timestamp_5e15c657"`)
        await db.query(`DROP INDEX "public"."idx_extrinsic_hash_fb7b0484"`)
        await db.query(`DROP INDEX "public"."idx_extrinsic_block_number_d3414dc1"`)
        await db.query(`DROP TABLE "extrinsic"`)
        await db.query(`DROP INDEX "public"."idx_block_timestamp_82806c7c"`)
        await db.query(`DROP INDEX "public"."idx_block_hash_575104b7"`)
        await db.query(`DROP INDEX "public"."idx_block_height_b20720ad"`)
        await db.query(`DROP TABLE "block"`)
    }
}
