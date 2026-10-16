import {Entity as Entity_, Column as Column_, PrimaryColumn as PrimaryColumn_, IntColumn as IntColumn_, Index as Index_, StringColumn as StringColumn_, DateTimeColumn as DateTimeColumn_} from "@subsquid/typeorm-store"

@Entity_()
export class Block {
    constructor(props?: Partial<Block>) {
        Object.assign(this, props)
    }

    @PrimaryColumn_()
    id!: string

    @Index_("idx_block_height_b20720ad")
    @IntColumn_({nullable: false})
    height!: number

    @Index_("idx_block_hash_575104b7")
    @StringColumn_({nullable: false})
    hash!: string

    @StringColumn_({nullable: false})
    parentHash!: string

    @Index_("idx_block_timestamp_82806c7c")
    @DateTimeColumn_({nullable: false})
    timestamp!: Date

    @IntColumn_({nullable: false})
    specVersion!: number

    @IntColumn_({nullable: false})
    extrinsicCount!: number

    @IntColumn_({nullable: false})
    eventCount!: number
}
